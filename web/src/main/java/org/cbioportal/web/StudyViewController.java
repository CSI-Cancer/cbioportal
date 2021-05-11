package org.cbioportal.web;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.map.MultiKeyMap;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.math3.util.Pair;
import org.cbioportal.model.*;
import org.cbioportal.model.util.Select;
import org.cbioportal.service.AlterationCountService;
import org.cbioportal.service.ClinicalAttributeService;
import org.cbioportal.service.ClinicalDataService;
import org.cbioportal.service.GenePanelService;
import org.cbioportal.service.MolecularProfileService;
import org.cbioportal.service.PatientService;
import org.cbioportal.service.SampleListService;
import org.cbioportal.service.SampleService;
import org.cbioportal.service.SignificantCopyNumberRegionService;
import org.cbioportal.service.SignificantlyMutatedGeneService;
import org.cbioportal.service.exception.StudyNotFoundException;
import org.cbioportal.service.util.ClinicalAttributeUtil;
import org.cbioportal.web.config.annotation.InternalApi;
import org.cbioportal.web.parameter.ClinicalDataBinCountFilter;
import org.cbioportal.web.parameter.ClinicalDataBinFilter;
import org.cbioportal.web.parameter.ClinicalDataCountFilter;
import org.cbioportal.web.parameter.ClinicalDataFilter;
import org.cbioportal.web.parameter.ClinicalDataType;
import org.cbioportal.web.parameter.DataBinMethod;
import org.cbioportal.web.parameter.GenericAssayDataBinCountFilter;
import org.cbioportal.web.parameter.GenomicDataBinCountFilter;
import org.cbioportal.web.parameter.Projection;
import org.cbioportal.web.parameter.SampleIdentifier;
import org.cbioportal.web.parameter.StudyViewFilter;
import org.cbioportal.web.util.DataBinner;
import org.cbioportal.web.util.StudyViewFilterApplier;
import org.cbioportal.web.util.StudyViewFilterUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import springfox.documentation.annotations.ApiIgnore;

import javax.annotation.PostConstruct;
import javax.validation.Valid;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@InternalApi
@RestController
@Validated
@Api(tags = "Study View", description = " ")
public class StudyViewController {
    @Autowired
    private ApplicationContext applicationContext;
    StudyViewController instance;
    @PostConstruct
    private void init() {
        instance = applicationContext.getBean(StudyViewController.class);
    }

    @Autowired
    private StudyViewFilterApplier studyViewFilterApplier;
    @Autowired
    private ClinicalDataService clinicalDataService;
    @Autowired
    private MolecularProfileService molecularProfileService;
    @Autowired
    private AlterationCountService alterationCountService;
    @Autowired
    private SampleService sampleService;
    @Autowired
    private PatientService patientService;
    @Autowired
    private GenePanelService genePanelService;
    @Autowired
    private SignificantlyMutatedGeneService significantlyMutatedGeneService;
    @Autowired
    private SignificantCopyNumberRegionService significantCopyNumberRegionService;
    @Autowired
    private DataBinner dataBinner;
    @Autowired
    private StudyViewFilterUtil studyViewFilterUtil;
    @Autowired
    private ClinicalAttributeService clinicalAttributeService;
    @Autowired
    private ClinicalAttributeUtil clinicalAttributeUtil;
    @Autowired
    private SampleListService sampleListService;

    private static final List<CNA> CNA_TYPES_AMP_AND_HOMDEL = Collections.unmodifiableList(Arrays.asList(CNA.AMP, CNA.HOMDEL));

    @PreAuthorize("hasPermission(#involvedCancerStudies, 'Collection<CancerStudyId>', 'read')")
    @RequestMapping(value = "/clinical-data-counts/fetch", method = RequestMethod.POST,
        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiOperation("Fetch clinical data counts by study view filter")
    public ResponseEntity<List<ClinicalDataCountItem>> fetchClinicalDataCounts(
        @ApiParam(required = true, value = "Clinical data count filter")
        @Valid @RequestBody(required = false)  ClinicalDataCountFilter clinicalDataCountFilter,
        @ApiIgnore // prevent reference to this attribute in the swagger-ui interface
        @RequestAttribute(required = false, value = "involvedCancerStudies") Collection<String> involvedCancerStudies,
        @ApiIgnore // prevent reference to this attribute in the swagger-ui interface. this attribute is needed for the @PreAuthorize tag above.
        @Valid @RequestAttribute(required = false, value = "interceptedClinicalDataCountFilter") ClinicalDataCountFilter interceptedClinicalDataCountFilter) {

        List<ClinicalDataFilter> attributes = interceptedClinicalDataCountFilter.getAttributes();
        StudyViewFilter studyViewFilter = interceptedClinicalDataCountFilter.getStudyViewFilter();
        if (attributes.size() == 1) {
            studyViewFilterUtil.removeSelfFromFilter(attributes.get(0).getAttributeId(), studyViewFilter);
        }
        List<SampleIdentifier> filteredSampleIdentifiers = studyViewFilterApplier.apply(studyViewFilter);
        
        if (filteredSampleIdentifiers.isEmpty()) {
            return new ResponseEntity<>(new ArrayList<>(), HttpStatus.OK);
        }
        List<String> studyIds = new ArrayList<>();
        List<String> sampleIds = new ArrayList<>();
        studyViewFilterUtil.extractStudyAndSampleIds(filteredSampleIdentifiers, studyIds, sampleIds);
        
        List<ClinicalDataCountItem> result = clinicalDataService.fetchClinicalDataCounts(
            studyIds, sampleIds, attributes.stream().map(a -> a.getAttributeId()).collect(Collectors.toList()));
        
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @PreAuthorize("hasPermission(#involvedCancerStudies, 'Collection<CancerStudyId>', 'read')")
    @RequestMapping(value = "/clinical-data-bin-counts/fetch", method = RequestMethod.POST,
        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiOperation("Fetch clinical data bin counts by study view filter")
    public ResponseEntity<List<ClinicalDataBin>> fetchClinicalDataBinCounts(
        @ApiParam("Method for data binning")
        @RequestParam(defaultValue = "DYNAMIC") DataBinMethod dataBinMethod,
        @ApiParam(required = true, value = "Clinical data bin count filter")
        @Valid @RequestBody(required = false) ClinicalDataBinCountFilter clinicalDataBinCountFilter,
        @ApiIgnore // prevent reference to this attribute in the swagger-ui interface
        @RequestAttribute(required = false, value = "involvedCancerStudies") Collection<String> involvedCancerStudies,
        @ApiIgnore // prevent reference to this attribute in the swagger-ui interface. this attribute is needed for the @PreAuthorize tag above.
        @Valid @RequestAttribute(required = false, value = "interceptedClinicalDataBinCountFilter") ClinicalDataBinCountFilter interceptedClinicalDataBinCountFilter
    ) {

        List<ClinicalDataBinFilter> attributes = interceptedClinicalDataBinCountFilter.getAttributes();
        StudyViewFilter studyViewFilter = interceptedClinicalDataBinCountFilter.getStudyViewFilter();

        if (attributes.size() == 1) {
            studyViewFilterUtil.removeSelfFromFilter(attributes.get(0).getAttributeId(), studyViewFilter);
        }

        List<ClinicalDataBin> clinicalDataBins = 
            instance.cachableFetchClinicalDataBinCounts(dataBinMethod, attributes, studyViewFilter);

        return new ResponseEntity<>(clinicalDataBins, HttpStatus.OK);
    }

    @Cacheable(
        cacheResolver = "staticRepositoryCacheOneResolver",
        condition = "@cacheEnabledConfig.getEnabled() && #studyViewFilter.isSingleStudyUnfiltered()"
    )
    public List<ClinicalDataBin> cachableFetchClinicalDataBinCounts(
        DataBinMethod dataBinMethod,
        List<ClinicalDataBinFilter> attributes,
        StudyViewFilter studyViewFilter
    ) {
        List<String> attributeIds = attributes.stream().map(ClinicalDataBinFilter::getAttributeId).collect(Collectors.toList());

        // filter only by study id and sample identifiers, ignore rest
        List<SampleIdentifier> unfilteredSampleIdentifiers = filterByStudyAndSample(studyViewFilter);

        List<String> unfilteredStudyIds = new ArrayList<>();
        List<String> unfilteredSampleIds = new ArrayList<>();
        List<String> unfilteredPatientIds = new ArrayList<>();
        List<String> studyIdsOfUnfilteredPatients = new ArrayList<>();
        List<String> unfilteredUniqueSampleKeys = new ArrayList<>();
        List<String> unfilteredUniquePatientKeys = new ArrayList<>();
        List<String> unfilteredSampleAttributeIds = new ArrayList<>();
        List<String> unfilteredPatientAttributeIds = new ArrayList<>();
        // patient attributes which are also sample attributes in other studies
        List<String> unfilteredConflictingPatientAttributeIds = new ArrayList<>();

        populateIdLists(
            // input
            unfilteredSampleIdentifiers,
            attributeIds,
            
            // output
            unfilteredStudyIds,
            unfilteredSampleIds,
            unfilteredPatientIds,
            studyIdsOfUnfilteredPatients,
            unfilteredUniqueSampleKeys,
            unfilteredUniquePatientKeys,
            unfilteredSampleAttributeIds,
            unfilteredPatientAttributeIds,
            unfilteredConflictingPatientAttributeIds
        );

        Map<String, ClinicalDataType> attributeDatatypeMap = constructAttributeDataMap(
            unfilteredSampleAttributeIds,
            unfilteredPatientAttributeIds,
            unfilteredConflictingPatientAttributeIds
        );

        List<ClinicalData> unfilteredClinicalDataForSamples = fetchClinicalDataForSamples(
            unfilteredStudyIds,
            unfilteredSampleIds,
            new ArrayList<>(unfilteredSampleAttributeIds)
        );

        List<ClinicalData> unfilteredClinicalDataForPatients = fetchClinicalDataForPatients(
            studyIdsOfUnfilteredPatients,
            unfilteredPatientIds,
            new ArrayList<>(unfilteredPatientAttributeIds)
        );

        List<ClinicalData> unfilteredClinicalDataForConflictingPatientAttributes = fetchClinicalDataForConflictingPatientAttributes(
            studyIdsOfUnfilteredPatients,
            unfilteredPatientIds,
            new ArrayList<>(unfilteredConflictingPatientAttributeIds)
        );

        List<ClinicalData> unfilteredClinicalData = Stream.of(
                unfilteredClinicalDataForSamples,
                unfilteredClinicalDataForPatients,
                unfilteredClinicalDataForConflictingPatientAttributes
            ).flatMap(Collection::stream).collect(Collectors.toList());

        // if filters are practically the same no need to re-apply them
        List<SampleIdentifier> filteredSampleIdentifiers = 
            studyViewFilterUtil.shouldSkipFilterForClinicalDataBins(studyViewFilter) ? 
                unfilteredSampleIdentifiers : studyViewFilterApplier.apply(studyViewFilter);

        List<String> filteredUniqueSampleKeys;
        List<String> filteredUniquePatientKeys;
        List<ClinicalData> filteredClinicalData;

        // if filtered and unfiltered samples are exactly the same, no need to fetch clinical data again
        if (filteredSampleIdentifiers.equals(unfilteredSampleIdentifiers)) {
            filteredUniqueSampleKeys = unfilteredUniqueSampleKeys;
            filteredUniquePatientKeys = unfilteredUniquePatientKeys;
            filteredClinicalData = unfilteredClinicalData;
        }
        else {
            List<String> filteredStudyIds = new ArrayList<>();
            List<String> filteredSampleIds = new ArrayList<>();
            List<String> filteredPatientIds = new ArrayList<>();
            List<String> studyIdsOfFilteredPatients = new ArrayList<>();
            filteredUniqueSampleKeys = new ArrayList<>();
            filteredUniquePatientKeys = new ArrayList<>();
            List<String> filteredSampleAttributeIds = new ArrayList<>();
            List<String> filteredPatientAttributeIds = new ArrayList<>();
            // patient attributes which are also sample attributes in other studies
            List<String> filteredConflictingPatientAttributeIds = new ArrayList<>();

            populateIdLists(
                // input
                filteredSampleIdentifiers,
                attributeIds,
                
                // output
                filteredStudyIds,
                filteredSampleIds,
                filteredPatientIds,
                studyIdsOfFilteredPatients,
                filteredUniqueSampleKeys,
                filteredUniquePatientKeys,
                filteredSampleAttributeIds,
                filteredPatientAttributeIds,
                filteredConflictingPatientAttributeIds
            );
            
            filteredClinicalData = studyViewFilterUtil.filterClinicalData(
                unfilteredClinicalDataForSamples,
                unfilteredClinicalDataForPatients,
                unfilteredClinicalDataForConflictingPatientAttributes,
                filteredStudyIds,
                filteredSampleIds,
                studyIdsOfFilteredPatients,
                filteredPatientIds,
                filteredSampleAttributeIds,
                filteredPatientAttributeIds,
                filteredConflictingPatientAttributeIds
            );
        }

        Map<String, List<ClinicalData>> unfilteredClinicalDataByAttributeId = 
            unfilteredClinicalData.stream().collect(Collectors.groupingBy(ClinicalData::getAttrId));

        Map<String, List<ClinicalData>> filteredClinicalDataByAttributeId =
            filteredClinicalData.stream().collect(Collectors.groupingBy(ClinicalData::getAttrId));

        List<ClinicalDataBin> clinicalDataBins = Collections.emptyList();

        if (dataBinMethod == DataBinMethod.STATIC) {
            if (!unfilteredSampleIdentifiers.isEmpty() && !unfilteredClinicalData.isEmpty()) {
                clinicalDataBins = calculateStaticDataBins(
                    attributes,
                    attributeDatatypeMap,
                    unfilteredClinicalDataByAttributeId,
                    filteredClinicalDataByAttributeId,
                    unfilteredUniqueSampleKeys,
                    unfilteredUniquePatientKeys,
                    filteredUniqueSampleKeys,
                    filteredUniquePatientKeys
                );
            }
        }
        else { // dataBinMethod == DataBinMethod.DYNAMIC
            if (!filteredClinicalData.isEmpty()) {
                clinicalDataBins = calculateDynamicDataBins(
                    attributes,
                    attributeDatatypeMap,
                    filteredClinicalDataByAttributeId,
                    filteredUniqueSampleKeys,
                    filteredUniquePatientKeys
                );
            }
        }
        return clinicalDataBins;
    }

    @PreAuthorize("hasPermission(#involvedCancerStudies, 'Collection<CancerStudyId>', 'read')")
    @RequestMapping(value = "/mutated-genes/fetch", method = RequestMethod.POST,
        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiOperation("Fetch mutated genes by study view filter")
    public ResponseEntity<List<AlterationCountByGene>> fetchMutatedGenes(
        @ApiParam(required = true, value = "Study view filter")
        @Valid @RequestBody(required = false) StudyViewFilter studyViewFilter,
        @ApiIgnore // prevent reference to this attribute in the swagger-ui interface
        @RequestAttribute(required = false, value = "involvedCancerStudies") Collection<String> involvedCancerStudies,
        @ApiIgnore // prevent reference to this attribute in the swagger-ui interface. this attribute is needed for the @PreAuthorize tag above.
        @Valid @RequestAttribute(required = false, value = "interceptedStudyViewFilter") StudyViewFilter interceptedStudyViewFilter
    ) throws StudyNotFoundException {
        List<AlterationCountByGene> result = instance.fetchMutatedGenesInner(interceptedStudyViewFilter);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @Cacheable(
        cacheResolver = "staticRepositoryCacheOneResolver",
        condition = "@cacheEnabledConfig.getEnabled() && #interceptedStudyViewFilter.isSingleStudyUnfiltered()"
    )
    public List<AlterationCountByGene> fetchMutatedGenesInner(StudyViewFilter interceptedStudyViewFilter) throws StudyNotFoundException {
        List<SampleIdentifier> filteredSampleIdentifiers = studyViewFilterApplier.apply(interceptedStudyViewFilter);
        Pair<List<AlterationCountByGene>, Long> resultPair = new Pair<>(new ArrayList<>(), 0L);
        if (!filteredSampleIdentifiers.isEmpty()) {
            List<String> studyIds = new ArrayList<>();
            List<String> sampleIds = new ArrayList<>();
            studyViewFilterUtil.extractStudyAndSampleIds(filteredSampleIdentifiers, studyIds, sampleIds);
            List<String> profileIdPerSample = molecularProfileService.getFirstMutationProfileIds(studyIds, sampleIds);
            List<MolecularProfileCaseIdentifier> caseIdentifiers = new ArrayList<>();
            for (int i = 0; i < profileIdPerSample.size(); i++) {
                caseIdentifiers.add(new MolecularProfileCaseIdentifier(sampleIds.get(i), profileIdPerSample.get(i)));
            }
            resultPair = alterationCountService.getSampleMutationCounts(
                caseIdentifiers,
                Select.all(),
                true, 
                false,
                Select.all());
            resultPair.getFirst().sort((a, b) -> b.getNumberOfAlteredCases() - a.getNumberOfAlteredCases());
            List<String> distinctStudyIds = studyIds.stream().distinct().collect(Collectors.toList());
            if (distinctStudyIds.size() == 1 && !resultPair.getFirst().isEmpty()) {
                Map<Integer, MutSig> mutSigMap = significantlyMutatedGeneService.getSignificantlyMutatedGenes(
                    distinctStudyIds.get(0), Projection.SUMMARY.name(), null, null, null, null).stream().collect(
                        Collectors.toMap(MutSig::getEntrezGeneId, Function.identity()));
                resultPair.getFirst().forEach(r -> {
                    if (mutSigMap.containsKey(r.getEntrezGeneId())) {
                        r.setqValue(mutSigMap.get(r.getEntrezGeneId()).getqValue());
                    }
                });
            }
        }
        return resultPair.getFirst();
    }

    @PreAuthorize("hasPermission(#involvedCancerStudies, 'Collection<CancerStudyId>', 'read')")
    @RequestMapping(value = "/structuralvariant-genes/fetch", method = RequestMethod.POST,
        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiOperation("Fetch structural variant genes by study view filter")
    public ResponseEntity<List<AlterationCountByGene>> fetchStructuralVariantGenes(
        @ApiParam(required = true, value = "Study view filter")
        @Valid @RequestBody(required = false) StudyViewFilter studyViewFilter,
        @ApiIgnore // prevent reference to this attribute in the swagger-ui interface. This attribute is needed for the @PreAuthorize tag above.
        @RequestAttribute(required = false, value = "involvedCancerStudies") Collection<String> involvedCancerStudies,
        @ApiIgnore // prevent reference to this attribute in the swagger-ui interface.
        @Valid @RequestAttribute(required = false, value = "interceptedStudyViewFilter") StudyViewFilter interceptedStudyViewFilter) throws StudyNotFoundException {
        List<SampleIdentifier> filteredSampleIdentifiers = studyViewFilterApplier.apply(interceptedStudyViewFilter);
        Pair<List<AlterationCountByGene>, Long> result = new Pair<>(new ArrayList<>(), 0L);
        if (!filteredSampleIdentifiers.isEmpty()) {
            List<String> studyIds = new ArrayList<>();
            List<String> sampleIds = new ArrayList<>();
            studyViewFilterUtil.extractStudyAndSampleIds(filteredSampleIdentifiers, studyIds, sampleIds);

            List<String> profileIdPerSample = molecularProfileService.getFirstStructuralVariantProfileIds(studyIds, sampleIds);
            List<MolecularProfileCaseIdentifier> caseIdentifiers = new ArrayList<>();
            for (int i = 0; i < profileIdPerSample.size(); i++) {
                caseIdentifiers.add(new MolecularProfileCaseIdentifier(sampleIds.get(i), profileIdPerSample.get(i)));
            }
            result = alterationCountService.getSampleStructuralVariantCounts(
                caseIdentifiers,
                Select.all(),
                true,
                false);
            result.getFirst().sort((a, b) -> b.getNumberOfAlteredCases() - a.getNumberOfAlteredCases());
            List<String> distinctStudyIds = studyIds.stream().distinct().collect(Collectors.toList());
            if (distinctStudyIds.size() == 1 && !result.getFirst().isEmpty()) {
                Map<Integer, MutSig> mutSigMap = significantlyMutatedGeneService.getSignificantlyMutatedGenes(
                    distinctStudyIds.get(0), Projection.SUMMARY.name(), null, null, null, null).stream().collect(
                    Collectors.toMap(MutSig::getEntrezGeneId, Function.identity()));
                result.getFirst().forEach(r -> {
                    if (mutSigMap.containsKey(r.getEntrezGeneId())) {
                        r.setqValue(mutSigMap.get(r.getEntrezGeneId()).getqValue());
                    }
                });
            }
        }

        return new ResponseEntity<>(result.getFirst(), HttpStatus.OK);
    }

    
    @PreAuthorize("hasPermission(#involvedCancerStudies, 'Collection<CancerStudyId>', 'read')")
    @RequestMapping(value = "/cna-genes/fetch", method = RequestMethod.POST,
        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiOperation("Fetch CNA genes by study view filter")
    public ResponseEntity<List<CopyNumberCountByGene>> fetchCNAGenes(
        @ApiParam(required = true, value = "Study view filter")
        @Valid @RequestBody(required = false) StudyViewFilter studyViewFilter,
        @ApiIgnore // prevent reference to this attribute in the swagger-ui interface
        @RequestAttribute(required = false, value = "involvedCancerStudies") Collection<String> involvedCancerStudies,
        @ApiIgnore // prevent reference to this attribute in the swagger-ui interface. this attribute is needed for the @PreAuthorize tag above.
        @Valid @RequestAttribute(required = false, value = "interceptedStudyViewFilter") StudyViewFilter interceptedStudyViewFilter) throws StudyNotFoundException {

        // TODO refactor resolution of sampleids to List<MolecularProfileCaseIdentifier> and share between methods
        List<SampleIdentifier> filteredSampleIdentifiers = studyViewFilterApplier.apply(interceptedStudyViewFilter);
        Pair<List<CopyNumberCountByGene>, Long> result = new Pair<>(new ArrayList<>(), 0L);
        if (!filteredSampleIdentifiers.isEmpty()) {
            List<String> studyIds = new ArrayList<>();
            List<String> sampleIds = new ArrayList<>();
            studyViewFilterUtil.extractStudyAndSampleIds(filteredSampleIdentifiers, studyIds, sampleIds);
            List<String> profileIdPerSample = molecularProfileService.getFirstDiscreteCNAProfileIds(studyIds, sampleIds);
            List<MolecularProfileCaseIdentifier> caseIdentifiers = new ArrayList<>();
            for (int i = 0; i < profileIdPerSample.size(); i++) {
                caseIdentifiers.add(new MolecularProfileCaseIdentifier(sampleIds.get(i), profileIdPerSample.get(i)));
            }
            Select<CNA> cnaTypes = Select.byValues(CNA_TYPES_AMP_AND_HOMDEL);
            result = alterationCountService.getSampleCnaCounts(
                caseIdentifiers, 
                Select.all(),
                true,
                false,
                cnaTypes);
            result.getFirst().sort((a, b) -> b.getNumberOfAlteredCases() - a.getNumberOfAlteredCases());
            List<String> distinctStudyIds = studyIds.stream().distinct().collect(Collectors.toList());
            if (distinctStudyIds.size() == 1 && !result.getFirst().isEmpty()) {
                List<Gistic> gisticList = significantCopyNumberRegionService.getSignificantCopyNumberRegions(
                    distinctStudyIds.get(0), Projection.SUMMARY.name(), null, null, null, null);
                MultiKeyMap gisticMap = new MultiKeyMap();
                gisticList.forEach(g -> g.getGenes().forEach(gene -> {
                    Gistic gistic = (Gistic) gisticMap.get(gene.getEntrezGeneId(), g.getAmp());
                    if (gistic == null || g.getqValue().compareTo(gistic.getqValue()) < 0) {
                        gisticMap.put(gene.getEntrezGeneId(), g.getAmp(), g);
                    }
                }));
                result.getFirst().forEach(r -> {
                    if (gisticMap.containsKey(r.getEntrezGeneId(), r.getAlteration().equals(2))) {
                        r.setqValue(((Gistic) gisticMap.get(r.getEntrezGeneId(), r.getAlteration().equals(2))).getqValue());
                    }
                });
            }
        }

        return new ResponseEntity<>(result.getFirst(), HttpStatus.OK);
    }

    @PreAuthorize("hasPermission(#involvedCancerStudies, 'Collection<CancerStudyId>', 'read')")
    @RequestMapping(value = "/filtered-samples/fetch", method = RequestMethod.POST,
        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiOperation("Fetch sample IDs by study view filter")
    public ResponseEntity<List<Sample>> fetchFilteredSamples(
        @ApiParam("Whether to negate the study view filters")
        @RequestParam(defaultValue = "false") Boolean negateFilters,
        @ApiIgnore // prevent reference to this attribute in the swagger-ui interface
        @RequestAttribute(required = false, value = "involvedCancerStudies") Collection<String> involvedCancerStudies,
        @ApiIgnore // prevent reference to this attribute in the swagger-ui interface. this attribute is needed for the @PreAuthorize tag above.
        @Valid @RequestAttribute(required = false, value = "interceptedStudyViewFilter") StudyViewFilter interceptedStudyViewFilter,
        @ApiParam(required = true, value = "Study view filter")
        @Valid @RequestBody(required = false) StudyViewFilter studyViewFilter) {

        List<String> studyIds = new ArrayList<>();
        List<String> sampleIds = new ArrayList<>();

        studyViewFilterUtil.extractStudyAndSampleIds(
            studyViewFilterApplier.apply(interceptedStudyViewFilter, negateFilters), studyIds, sampleIds);

        List<Sample> result = new ArrayList<>();
        if (!sampleIds.isEmpty()) {
            result = sampleService.fetchSamples(studyIds, sampleIds, Projection.ID.name());
        }
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @PreAuthorize("hasPermission(#involvedCancerStudies, 'Collection<CancerStudyId>', 'read')")
    @RequestMapping(value = "/molecular-profile-sample-counts/fetch", method = RequestMethod.POST,
        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiOperation("Fetch sample counts by study view filter")
    public List<GenomicDataCount> fetchMolecularProfileSampleCounts(
        @ApiParam(required = true, value = "Study view filter")
        @Valid @RequestBody(required = false) StudyViewFilter studyViewFilter,
        @ApiIgnore // prevent reference to this attribute in the swagger-ui interface
        @RequestAttribute(required = false, value = "involvedCancerStudies") Collection<String> involvedCancerStudies,
        @ApiIgnore // prevent reference to this attribute in the swagger-ui interface. this attribute is needed for the @PreAuthorize tag above.
        @Valid @RequestAttribute(required = false, value = "interceptedStudyViewFilter") StudyViewFilter interceptedStudyViewFilter) {

        List<String> studyIds = new ArrayList<>();
        List<String> sampleIds = new ArrayList<>();
        studyViewFilterUtil.extractStudyAndSampleIds(studyViewFilterApplier.apply(interceptedStudyViewFilter), studyIds,
                sampleIds);
        List<MolecularProfile> molecularProfiles = molecularProfileService.getMolecularProfilesInStudies(new ArrayList<>(new HashSet<>(studyIds)),
                "SUMMARY");

        Map<String, List<MolecularProfile>> studyMolecularProfilesSet = molecularProfiles
            .stream()
            .collect(Collectors.groupingBy(MolecularProfile::getCancerStudyIdentifier))
            .entrySet().stream().collect(Collectors.toMap(
                entry -> entry.getKey(),
                entry -> {
                    List<MolecularProfile> profilesToReturn = new ArrayList<>();
                    MolecularProfile structuralVariantProfile = null;
                    for (MolecularProfile molecularProfile : entry.getValue()) {
                        if (molecularProfile.getMolecularAlterationType().equals(MolecularProfile.MolecularAlterationType.FUSION)
                            || molecularProfile.getMolecularAlterationType()
                            .equals(MolecularProfile.MolecularAlterationType.STRUCTURAL_VARIANT)) {
                            if (structuralVariantProfile == null) {
                                structuralVariantProfile = molecularProfile;
                            } else if (!(molecularProfile.getMolecularAlterationType()
                                .equals(MolecularProfile.MolecularAlterationType.STRUCTURAL_VARIANT)
                                && molecularProfile.getDatatype().equals("SV"))) {
                                // replace structural variant profile with
                                // mutation profile having fusion data
                                structuralVariantProfile= molecularProfile;
                            }
                        } else {
                            profilesToReturn.add(molecularProfile);
                        }
                    }

                    if (structuralVariantProfile != null) {
                        profilesToReturn.add(structuralVariantProfile);
                    }

                    return profilesToReturn;
            }));

        List<MolecularProfileCaseIdentifier> molecularProfileSampleIdentifiers = new ArrayList<>();

        for (int i = 0; i < studyIds.size(); i++) {
            String studyId = studyIds.get(i);
            String sampleId = sampleIds.get(i);
            if (studyMolecularProfilesSet.containsKey(studyId)) {
                studyMolecularProfilesSet.get(studyId).stream().forEach(molecularProfile -> {
                    MolecularProfileCaseIdentifier profileCaseIdentifier = new MolecularProfileCaseIdentifier();
                    profileCaseIdentifier.setMolecularProfileId(molecularProfile.getStableId());
                    profileCaseIdentifier.setCaseId(sampleId);
                    molecularProfileSampleIdentifiers.add(profileCaseIdentifier);
                });
            }
        }

        List<GenePanelData> genePanelData = genePanelService
                .fetchGenePanelDataInMultipleMolecularProfiles(molecularProfileSampleIdentifiers);
        HashMap<String, Integer> molecularProfileSampleCountSet = new HashMap<>();

        for (GenePanelData datum : genePanelData) {
            if (datum.getProfiled()) {
                Integer count = molecularProfileSampleCountSet.getOrDefault(datum.getMolecularProfileId(), 0);
                molecularProfileSampleCountSet.put(datum.getMolecularProfileId(), count + 1);
            }
        }

        Map<String, List<MolecularProfile>> molecularProfileSet = studyViewFilterUtil
                .categorizeMolecularPorfiles(molecularProfiles);

        return molecularProfileSet
                .entrySet()
                .stream()
                .map(entry -> {
                    GenomicDataCount dataCount = new GenomicDataCount();
                    dataCount.setValue(entry.getKey());

                    Integer count = entry
                        .getValue()
                        .stream()
                        .mapToInt(molecularProfile -> molecularProfileSampleCountSet.getOrDefault(molecularProfile.getStableId(), 0))
                        .sum();
        
                    dataCount.setCount(count);
                    dataCount.setLabel(entry.getValue().get(0).getName());
        
                    return dataCount;
                })
                .filter(dataCount -> dataCount.getCount() > 0)
                .collect(Collectors.toList());

    }

    @PreAuthorize("hasPermission(#involvedCancerStudies, 'Collection<CancerStudyId>', 'read')")
    @RequestMapping(value = "/clinical-data-density-plot/fetch", method = RequestMethod.POST,
        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiOperation("Fetch clinical data density plot bins by study view filter")
    public ResponseEntity<List<DensityPlotBin>> fetchClinicalDataDensityPlot(
        @ApiParam(required = true, value = "Clinical Attribute ID of the X axis")
        @RequestParam String xAxisAttributeId,
        @ApiParam("Number of the bins in X axis")
        @RequestParam(defaultValue = "50") Integer xAxisBinCount,
        @ApiParam("Starting point of the X axis, if different than smallest value")
        @RequestParam(required = false) BigDecimal xAxisStart,
        @ApiParam("Starting point of the X axis, if different than largest value")
        @RequestParam(required = false) BigDecimal xAxisEnd,
        @ApiParam(required = true, value = "Clinical Attribute ID of the Y axis")
        @RequestParam String yAxisAttributeId,
        @ApiParam("Number of the bins in Y axis")
        @RequestParam(defaultValue = "50") Integer yAxisBinCount,
        @ApiParam("Starting point of the Y axis, if different than smallest value")
        @RequestParam(required = false) BigDecimal yAxisStart,
        @ApiParam("Starting point of the Y axis, if different than largest value")
        @RequestParam(required = false) BigDecimal yAxisEnd,
        @ApiIgnore // prevent reference to this attribute in the swagger-ui interface
        @RequestAttribute(required = false, value = "involvedCancerStudies") Collection<String> involvedCancerStudies,
        @ApiIgnore // prevent reference to this attribute in the swagger-ui interface. this attribute is needed for the @PreAuthorize tag above.
        @Valid @RequestAttribute(required = false, value = "interceptedStudyViewFilter") StudyViewFilter interceptedStudyViewFilter,
        @ApiParam(required = true, value = "Study view filter")
        @Valid @RequestBody(required = false) StudyViewFilter studyViewFilter) {

        List<String> studyIds = new ArrayList<>();
        List<String> sampleIds = new ArrayList<>();
        studyViewFilterUtil.extractStudyAndSampleIds(studyViewFilterApplier.apply(interceptedStudyViewFilter), studyIds, sampleIds);
        List<DensityPlotBin> result = new ArrayList<>();
        if (sampleIds.isEmpty()) {
            return new ResponseEntity<>(result, HttpStatus.OK);
        }

        List<String> sampleAttributeIds = new ArrayList<>();
        List<String> patientAttributeIds = new ArrayList<>();
        
        List<ClinicalAttribute> clinicalAttributes = clinicalAttributeService
                .getClinicalAttributesByStudyIdsAndAttributeIds(studyIds,
                        Arrays.asList(xAxisAttributeId, yAxisAttributeId));

        clinicalAttributeUtil.extractCategorizedClinicalAttributes(clinicalAttributes, sampleAttributeIds, patientAttributeIds, patientAttributeIds);

        List<Patient> patients = new ArrayList<>();
        List<String> patientIds = new ArrayList<>();
        List<String> studyIdsOfPatients = new ArrayList<>();

        if (CollectionUtils.isNotEmpty(patientAttributeIds)) {
            patients = patientService.getPatientsOfSamples(studyIds, sampleIds).stream().collect(Collectors.toList());
            patientIds = patients.stream().map(Patient::getStableId).collect(Collectors.toList());
            studyIdsOfPatients = patients.stream().map(Patient::getCancerStudyIdentifier).collect(Collectors.toList());
        }

        List<ClinicalData> clinicalDataList = fetchClinicalData(studyIds, sampleIds, patientIds, studyIdsOfPatients,
                sampleAttributeIds, patientAttributeIds, null);

        Map<String, Map<String, List<ClinicalData>>> clinicalDataMap;
        if (!sampleAttributeIds.isEmpty()) {
            clinicalDataMap = clinicalDataList.stream().collect(Collectors.groupingBy(ClinicalData::getSampleId, 
                Collectors.groupingBy(ClinicalData::getStudyId)));
        } else {
            clinicalDataMap = clinicalDataList.stream().collect(Collectors.groupingBy(ClinicalData::getPatientId,
                Collectors.groupingBy(ClinicalData::getStudyId)));
        }
        
        List<ClinicalData> filteredClinicalDataList = new ArrayList<>();
        clinicalDataMap.forEach((k, v) -> v.forEach((m, n) -> {
            if (n.size() == 2 && NumberUtils.isNumber(n.get(0).getAttrValue()) && NumberUtils.isNumber(n.get(1).getAttrValue())) {
                filteredClinicalDataList.addAll(n);
            }
        }));
        if (filteredClinicalDataList.isEmpty()) {
            return new ResponseEntity<>(result, HttpStatus.OK);
        }
        
        Map<Boolean, List<ClinicalData>> partition = filteredClinicalDataList.stream().collect(
            Collectors.partitioningBy(c -> c.getAttrId().equals(xAxisAttributeId)));
        double[] xValues = partition.get(true).stream().mapToDouble(c -> Double.parseDouble(c.getAttrValue())).toArray();
        double[] yValues = partition.get(false).stream().mapToDouble(c -> Double.parseDouble(c.getAttrValue())).toArray();
        double[] xValuesCopy = Arrays.copyOf(xValues, xValues.length);
        double[] yValuesCopy = Arrays.copyOf(yValues, yValues.length);
        Arrays.sort(xValuesCopy);
        Arrays.sort(yValuesCopy);

        double xAxisStartValue = xAxisStart == null ? xValuesCopy[0] : xAxisStart.doubleValue();
        double xAxisEndValue = xAxisEnd == null ? xValuesCopy[xValuesCopy.length - 1] : xAxisEnd.doubleValue();
        double yAxisStartValue = yAxisStart == null ? yValuesCopy[0] : yAxisStart.doubleValue();
        double yAxisEndValue = yAxisEnd == null ? yValuesCopy[yValuesCopy.length - 1] : yAxisEnd.doubleValue();
        double xAxisBinInterval = (xAxisEndValue - xAxisStartValue) / xAxisBinCount;
        double yAxisBinInterval = (yAxisEndValue - yAxisStartValue) / yAxisBinCount;
        for (int i = 0; i < xAxisBinCount; i++) {
            for (int j = 0; j < yAxisBinCount; j++) {
                DensityPlotBin densityPlotBin = new DensityPlotBin();
                densityPlotBin.setBinX(new BigDecimal(xAxisStartValue + (i * xAxisBinInterval)));
                densityPlotBin.setBinY(new BigDecimal(yAxisStartValue + (j * yAxisBinInterval)));
                densityPlotBin.setCount(0);
                result.add(densityPlotBin);
            }
        }

        for (int i = 0; i < xValues.length; i++) {
            double xValue = xValues[i];
            double yValue = yValues[i];
            int xBinIndex = (int) ((xValue - xAxisStartValue) / xAxisBinInterval);
            int yBinIndex = (int) ((yValue - yAxisStartValue) / yAxisBinInterval);
            int index = (int) (((xBinIndex - (xBinIndex == xAxisBinCount ? 1 : 0)) * yAxisBinCount) +
                (yBinIndex - (yBinIndex == yAxisBinCount ? 1 : 0)));
            DensityPlotBin densityPlotBin = result.get(index);
            densityPlotBin.setCount(densityPlotBin.getCount() + 1);
            BigDecimal xValueBigDecimal = new BigDecimal(xValue);
            BigDecimal yValueBigDecimal = new BigDecimal(yValue);
            if (densityPlotBin.getMinX() != null) {
                if (densityPlotBin.getMinX().compareTo(xValueBigDecimal) > 0) {
                    densityPlotBin.setMinX(xValueBigDecimal);
                }
            } else {
                densityPlotBin.setMinX(xValueBigDecimal);
            }
            if (densityPlotBin.getMaxX() != null) {
                if (densityPlotBin.getMaxX().compareTo(xValueBigDecimal) < 0) {
                    densityPlotBin.setMaxX(xValueBigDecimal);
                }
            } else {
                densityPlotBin.setMaxX(xValueBigDecimal);
            }
            if (densityPlotBin.getMinY() != null) {
                if (densityPlotBin.getMinY().compareTo(yValueBigDecimal) > 0) {
                    densityPlotBin.setMinY(yValueBigDecimal);
                }
            } else {
                densityPlotBin.setMinY(yValueBigDecimal);
            }
            if (densityPlotBin.getMaxY() != null) {
                if (densityPlotBin.getMaxY().compareTo(yValueBigDecimal) < 0) {
                    densityPlotBin.setMaxY(yValueBigDecimal);
                }
            } else {
                densityPlotBin.setMaxY(yValueBigDecimal);
            }
        }

        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @PreAuthorize("hasPermission(#involvedCancerStudies, 'Collection<CancerStudyId>', 'read')")
    @RequestMapping(value = "/sample-lists-counts/fetch", method = RequestMethod.POST,
        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiOperation("Fetch case list sample counts by study view filter")
    public List<CaseListDataCount> fetchCaseListCounts(
        @ApiParam(required = true, value = "Study view filter")
        @Valid @RequestBody(required = false) StudyViewFilter studyViewFilter,
        @ApiIgnore // prevent reference to this attribute in the swagger-ui interface
        @RequestAttribute(required = false, value = "involvedCancerStudies") Collection<String> involvedCancerStudies,
        @ApiIgnore // prevent reference to this attribute in the swagger-ui interface. this attribute is needed for the @PreAuthorize tag above.
        @Valid @RequestAttribute(required = false, value = "interceptedStudyViewFilter") StudyViewFilter interceptedStudyViewFilter) {

        List<String> studyIds = new ArrayList<>();
        List<String> sampleIds = new ArrayList<>();
        List<SampleIdentifier> filteredSampleIdentifiers = studyViewFilterApplier.apply(interceptedStudyViewFilter);
        studyViewFilterUtil.extractStudyAndSampleIds(filteredSampleIdentifiers, studyIds, sampleIds);
        List<SampleList> sampleLists = sampleListService.getAllSampleListsInStudies(studyIds,
                Projection.DETAILED.name());

        HashMap<String, Integer> sampleCountBySampleListId = new HashMap<String, Integer>();

        Map<String, SampleIdentifier> filteredSampleSet = filteredSampleIdentifiers.stream()
                .collect(Collectors.toMap(sampleidentifier -> studyViewFilterUtil
                        .getCaseUniqueKey(sampleidentifier.getStudyId(), sampleidentifier.getSampleId()),
                        Function.identity()));

        for (SampleList sampleList : sampleLists) {
            for (String sampleId : sampleList.getSampleIds()) {
                if (filteredSampleSet.containsKey(
                        studyViewFilterUtil.getCaseUniqueKey(sampleList.getCancerStudyIdentifier(), sampleId))) {
                    Integer count = sampleCountBySampleListId.getOrDefault(sampleList.getStableId(), 0);
                    sampleCountBySampleListId.put(sampleList.getStableId(), count + 1);
                }
            }
        }

        return studyViewFilterUtil
                .categorizeSampleLists(sampleLists)
                .entrySet()
                .stream()
                .map(entry -> {
                    CaseListDataCount dataCount = new CaseListDataCount();
                    dataCount.setValue(entry.getKey());
        
                    Integer count = entry.getValue().stream().mapToInt(sampleList -> {
                        return sampleCountBySampleListId.getOrDefault(sampleList.getStableId(), 0);
                    }).sum();
        
                    dataCount.setCount(count);
                    dataCount.setLabel(entry.getValue().get(0).getName());
        
                    return dataCount;
                })
                .filter(dataCount -> dataCount.getCount() > 0)
                .collect(Collectors.toList());

    }

    private void populateIdLists(
        // input lists
        List<SampleIdentifier> sampleIdentifiers,
        List<String> attributeIds,
        // lists to get populated
        List<String> studyIds,
        List<String> sampleIds,
        List<String> patientIds,
        List<String> studyIdsOfPatients,
        List<String> uniqueSampleKeys,
        List<String> uniquePatientKeys,
        List<String> sampleAttributeIds,
        List<String> patientAttributeIds,
        List<String> conflictingPatientAttributeIds
    ) {
        studyViewFilterUtil.extractStudyAndSampleIds(
            sampleIdentifiers,
            studyIds,
            sampleIds
        );

        patientService.getPatientsOfSamples(studyIds, sampleIds).stream().forEach(patient -> {
            patientIds.add(patient.getStableId());
            studyIdsOfPatients.add(patient.getCancerStudyIdentifier());
        });

        uniqueSampleKeys.addAll(studyViewFilterApplier.getUniqkeyKeys(studyIds, sampleIds));
        uniquePatientKeys.addAll(studyViewFilterApplier.getUniqkeyKeys(studyIdsOfPatients, patientIds));

        if (attributeIds != null) {
            List<ClinicalAttribute> clinicalAttributes = clinicalAttributeService
                .getClinicalAttributesByStudyIdsAndAttributeIds(studyIds, attributeIds);

            clinicalAttributeUtil.extractCategorizedClinicalAttributes(
                clinicalAttributes,
                sampleAttributeIds,
                patientAttributeIds,
                conflictingPatientAttributeIds
            );
        }
    }
    
    private List<ClinicalDataBin> calculateStaticDataBins(
        List<ClinicalDataBinFilter> attributes,
        Map<String, ClinicalDataType> attributeDatatypeMap,
        Map<String, List<ClinicalData>> unfilteredClinicalDataByAttributeId,
        Map<String, List<ClinicalData>> filteredClinicalDataByAttributeId,
        List<String> unfilteredUniqueSampleKeys,
        List<String> unfilteredUniquePatientKeys,
        List<String> filteredUniqueSampleKeys,
        List<String> filteredUniquePatientKeys
    ) {
        List<ClinicalDataBin> clinicalDataBins = new ArrayList<>();
        
        for (ClinicalDataBinFilter attribute : attributes) {
            if (attributeDatatypeMap.containsKey(attribute.getAttributeId())) {
                ClinicalDataType clinicalDataType = attributeDatatypeMap.get(attribute.getAttributeId());
                List<String> filteredIds = clinicalDataType == ClinicalDataType.PATIENT ? filteredUniquePatientKeys
                    : filteredUniqueSampleKeys;
                List<String> unfilteredIds = clinicalDataType == ClinicalDataType.PATIENT
                    ? unfilteredUniquePatientKeys
                    : unfilteredUniqueSampleKeys;

                List<ClinicalDataBin> dataBins = dataBinner
                    .calculateClinicalDataBins(attribute, clinicalDataType,
                        filteredClinicalDataByAttributeId.getOrDefault(attribute.getAttributeId(),
                            Collections.emptyList()),
                        unfilteredClinicalDataByAttributeId.getOrDefault(attribute.getAttributeId(),
                            Collections.emptyList()),
                        filteredIds, unfilteredIds)
                    .stream()
                    .map(dataBin -> studyViewFilterUtil.dataBinToClinicalDataBin(attribute, dataBin))
                    .collect(Collectors.toList());

                clinicalDataBins.addAll(dataBins);
            }
        }
        
        return clinicalDataBins;
    }

    private List<ClinicalDataBin> calculateDynamicDataBins(
        List<ClinicalDataBinFilter> attributes,
        Map<String, ClinicalDataType> attributeDatatypeMap,
        Map<String, List<ClinicalData>> filteredClinicalDataByAttributeId,
        List<String> filteredUniqueSampleKeys,
        List<String> filteredUniquePatientKeys
    ) {
        List<ClinicalDataBin> clinicalDataBins = new ArrayList<>();

        for (ClinicalDataBinFilter attribute : attributes) {

            if (attributeDatatypeMap.containsKey(attribute.getAttributeId())) {
                ClinicalDataType clinicalDataType = attributeDatatypeMap.get(attribute.getAttributeId());
                List<String> filteredIds = clinicalDataType == ClinicalDataType.PATIENT
                    ? filteredUniquePatientKeys
                    : filteredUniqueSampleKeys;

                List<ClinicalDataBin> dataBins = dataBinner
                    .calculateDataBins(attribute, clinicalDataType,
                        filteredClinicalDataByAttributeId.getOrDefault(attribute.getAttributeId(),
                            Collections.emptyList()),
                        filteredIds)
                    .stream()
                    .map(dataBin -> studyViewFilterUtil.dataBinToClinicalDataBin(attribute, dataBin))
                    .collect(Collectors.toList());
                clinicalDataBins.addAll(dataBins);
            }
        }

        return clinicalDataBins;
    }
    
    private Map<String, ClinicalDataType> constructAttributeDataMap(
        List<String> sampleAttributeIds,
        List<String> patientAttributeIds,
        List<String> conflictingPatientAttributeIds
    ) {
        Map<String, ClinicalDataType> attributeDatatypeMap = new HashMap<>();

        sampleAttributeIds.forEach(attribute->{
            attributeDatatypeMap.put(attribute, ClinicalDataType.SAMPLE);
        });
        patientAttributeIds.forEach(attribute->{
            attributeDatatypeMap.put(attribute, ClinicalDataType.PATIENT);
        });
        conflictingPatientAttributeIds.forEach(attribute->{
            attributeDatatypeMap.put(attribute, ClinicalDataType.SAMPLE);
        });
        
        return attributeDatatypeMap;
    }

    private List<SampleIdentifier> filterByStudyAndSample(
        StudyViewFilter studyViewFilter
    ) {
        StudyViewFilter filter = null;

        // only filter by study id and sample identifiers
        if (studyViewFilter != null) {
            filter = new StudyViewFilter();
            filter.setStudyIds(studyViewFilter.getStudyIds());
            filter.setSampleIdentifiers(studyViewFilter.getSampleIdentifiers());
        }

        return studyViewFilterApplier.apply(filter);
    }

    private List<ClinicalData> fetchClinicalDataForSamples(
        List<String> studyIds,
        List<String> sampleIds,
        List<String> sampleAttributeIds
    ) {
        List<ClinicalData> filteredClinicalDataForSamples = Collections.emptyList();

        if (CollectionUtils.isNotEmpty(sampleAttributeIds)) {
            filteredClinicalDataForSamples = clinicalDataService.fetchClinicalData(
                studyIds,
                sampleIds,
                sampleAttributeIds, 
                "SAMPLE", 
                Projection.SUMMARY.name()
            );
        }
        
        return filteredClinicalDataForSamples;
    }

    private List<ClinicalData> fetchClinicalDataForPatients(
        List<String> studyIdsOfPatients,
        List<String> patientIds,
        List<String> patientAttributeIds
    ) {
        List<ClinicalData> filteredClinicalDataForPatients = Collections.emptyList();

        if (CollectionUtils.isNotEmpty(patientAttributeIds)) {
            filteredClinicalDataForPatients = clinicalDataService.fetchClinicalData(
                studyIdsOfPatients,
                patientIds,
                patientAttributeIds,
                "PATIENT",
                Projection.SUMMARY.name()
            );
        }
        
        return filteredClinicalDataForPatients;
    }
    
    private List<ClinicalData> fetchClinicalDataForConflictingPatientAttributes(
        List<String> studyIdsOfPatients,
        List<String> patientIds,
        List<String> conflictingPatientAttributes
    ) {
        List<ClinicalData> filteredClinicalDataForPatients = Collections.emptyList();
        
        if (CollectionUtils.isNotEmpty(conflictingPatientAttributes)) {
            filteredClinicalDataForPatients = clinicalDataService.getPatientClinicalDataDetailedToSample(
                studyIdsOfPatients,
                patientIds,
                conflictingPatientAttributes
            );
        }

        return filteredClinicalDataForPatients;
    } 
    
    private List<ClinicalData> fetchClinicalData(
        List<String> studyIds,
        List<String> sampleIds,
        List<String> patientIds,
        List<String> studyIdsOfPatients,
        List<String> sampleAttributeIds,
        List<String> patientAttributeIds,
        List<String> conflictingPatientAttributes
    ) {
        List<ClinicalData> unfilteredClinicalDataForSamples = fetchClinicalDataForSamples(
            studyIds,
            sampleIds,
            sampleAttributeIds
        );

        List<ClinicalData> unfilteredClinicalDataForPatients = fetchClinicalDataForPatients(
            studyIdsOfPatients,
            patientIds,
            patientAttributeIds
        );

        List<ClinicalData> unfilteredClinicalDataForConflictingPatientAttributes = fetchClinicalDataForConflictingPatientAttributes(
            studyIdsOfPatients,
            patientIds,
            conflictingPatientAttributes
        );

        return Stream.of(
            unfilteredClinicalDataForSamples,
            unfilteredClinicalDataForPatients,
            unfilteredClinicalDataForConflictingPatientAttributes
        ).flatMap(Collection::stream).collect(Collectors.toList());
    }
    
    @PreAuthorize("hasPermission(#involvedCancerStudies, 'Collection<CancerStudyId>', 'read')")
    @RequestMapping(value = "/genomic-data-bin-counts/fetch", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiOperation("Fetch genomic data bin counts by study view filter")
    public ResponseEntity<List<GenomicDataBin>> fetchGenomicDataBinCounts(
            @ApiParam("Method for data binning") @RequestParam(defaultValue = "DYNAMIC") DataBinMethod dataBinMethod,
            @ApiParam(required = true, value = "Genomic data bin count filter") @Valid @RequestBody(required = false) GenomicDataBinCountFilter genomicDataBinCountFilter,
            @ApiIgnore // prevent reference to this attribute in the swagger-ui interface
            @RequestAttribute(required = false, value = "involvedCancerStudies") Collection<String> involvedCancerStudies,
            @ApiIgnore // prevent reference to this attribute in the swagger-ui interface. this
                       // attribute is needed for the @PreAuthorize tag above.
            @Valid @RequestAttribute(required = false, value = "interceptedGenomicDataBinCountFilter") GenomicDataBinCountFilter interceptedGenomicDataBinCountFilter) {

        return new ResponseEntity<>(studyViewFilterApplier.getDataBins(dataBinMethod, interceptedGenomicDataBinCountFilter), HttpStatus.OK);
    }

    @PreAuthorize("hasPermission(#involvedCancerStudies, 'Collection<CancerStudyId>', 'read')")
    @RequestMapping(value = "/generic-assay-data-bin-counts/fetch", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiOperation("Fetch generic assay data bin counts by study view filter")
    public ResponseEntity<List<GenericAssayDataBin>> fetchGenericAssayDataBinCounts(
            @ApiParam("Method for data binning") @RequestParam(defaultValue = "DYNAMIC") DataBinMethod dataBinMethod,
            @ApiParam(required = true, value = "Generic assay data bin count filter") @Valid @RequestBody(required = false) GenericAssayDataBinCountFilter genericAssayDataBinCountFilter,
            @ApiIgnore // prevent reference to this attribute in the swagger-ui interface
            @RequestAttribute(required = false, value = "involvedCancerStudies") Collection<String> involvedCancerStudies,
            @ApiIgnore // prevent reference to this attribute in the swagger-ui interface. this
                        // attribute is needed for the @PreAuthorize tag above.
            @Valid @RequestAttribute(required = false, value = "interceptedGenericAssayDataBinCountFilter") GenericAssayDataBinCountFilter interceptedGenericAssayDataBinCountFilter) {

        return new ResponseEntity<>(studyViewFilterApplier.getDataBins(dataBinMethod, interceptedGenericAssayDataBinCountFilter), HttpStatus.OK);
    }
}
