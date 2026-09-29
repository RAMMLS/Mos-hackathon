package ru.moshackathon.heatnetwork.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.moshackathon.heatnetwork.model.Solution;
import ru.moshackathon.heatnetwork.service.TraceService;
import ru.moshackathon.heatnetwork.solver.AlgorithmId;
import ru.moshackathon.heatnetwork.solver.AlgorithmUnavailableException;
import ru.moshackathon.heatnetwork.solver.RoutePlanner;
import ru.moshackathon.heatnetwork.solver.NoCertifiedSolutionException;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/trace")
@Tag(name = "Heat network routing", description = "Build and validate heat-network connection variants")
public class TraceController {
    private static final MediaType GEO_JSON = MediaType.parseMediaType("application/geo+json");
    private final TraceService traceService;

    public TraceController(TraceService traceService) {
        this.traceService = traceService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/geo+json")
    @Operation(summary = "Build a heat-network connection variant",
            description = "Accepts one combined GeoJSON FeatureCollection and streams one result FeatureCollection.")
    @ApiResponse(responseCode = "200", description = "Calculated GeoJSON variant",
            content = @Content(mediaType = "application/geo+json", schema = @Schema(type = "object")))
    @ApiResponse(responseCode = "400", description = "Invalid input or request parameters")
    @ApiResponse(responseCode = "413", description = "Input exceeds 3 GB")
    public ResponseEntity<StreamingResponseBody> trace(
                                        @Parameter(description = "Combined input GeoJSON, up to 3 GB", required = true)
                                        @RequestParam("file") MultipartFile file,
                                        @RequestParam(value = "algorithm", required = false) String algorithm,
                                        @RequestParam(value = "budgetMs", defaultValue = "0") long budgetMs,
                                        @RequestParam(value = "entryStrategy", defaultValue = "AUTO") String entryStrategy,
                                        @RequestParam(value = "ruleset", defaultValue = "CLARIFIED_EXTERIOR_BOUNDARY_V1") String ruleSet,
                                        @RequestParam(value = "diagnosticFallback", defaultValue = "false")
                                        boolean diagnosticFallback)
            throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("input GeoJSON file must not be empty");
        }
        if (budgetMs < 0 || budgetMs > 3_600_000) {
            throw new IllegalArgumentException("budgetMs must be between 0 and 3600000");
        }
        RoutePlanner.RuleSet parsedRuleSet = parseRuleSet(ruleSet);
        Solution solution = traceService.solve(
                file.getInputStream(), AlgorithmId.parse(algorithm), budgetMs,
                parseEntryStrategy(entryStrategy), parsedRuleSet, diagnosticFallback);
        StreamingResponseBody body = output -> traceService.write(solution, output);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(GEO_JSON);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("heat-network-result.geojson").build());
        headers.set("X-Solution-Certified", Boolean.toString(solution.isCertified()));
        headers.set("X-Solution-Complete", Boolean.toString(solution.isComplete()));
        headers.set("X-Solution-Status", solution.getSolutionStatus());
        headers.set("X-Full-Connectivity-Status", solution.getFullConnectivityStatus());
        headers.set("X-Solution-Coverage", solution.getConnectedConnectionPointCount()
                + "/" + solution.getTotalConnectionPointCount());
        headers.set("X-Ruleset-Id", parsedRuleSet.name());
        headers.set("X-Ruleset-Hash", parsedRuleSet.getProfileHash());
        headers.set("X-Ruleset-Experimental", Boolean.toString(parsedRuleSet.isExperimental()));
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private RoutePlanner.RuleSet parseRuleSet(String value) {
        try {
            return RoutePlanner.RuleSet.valueOf(value.trim().toUpperCase());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "ruleset must be CLARIFIED_EXTERIOR_BOUNDARY_V1, DOCUMENT_NEAREST_V1, or EXPERIMENTAL_ANY_BOUNDARY_V1");
        }
    }

    private RoutePlanner.EntryStrategy parseEntryStrategy(String value) {
        if (value == null || value.trim().isEmpty() || "AUTO".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return RoutePlanner.EntryStrategy.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "entryStrategy must be AUTO, DIRECT_ALLOWED, or PORTAL_ONLY");
        }
    }

    @GetMapping(value = "/algorithms", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> algorithms() {
        return Arrays.stream(AlgorithmId.values()).map(item -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", item.getExternalName());
            value.put("status", item.getStatus());
            value.put("runnable", item.isRunnable());
            value.put("description", item.getDescription());
            return value;
        }).collect(Collectors.toList());
    }

    @GetMapping(value = "/configuration", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Read the routing API contract and production defaults")
    public Map<String, Object> configuration() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("default_algorithm", AlgorithmId.PORTFOLIO.getExternalName());
        value.put("default_ruleset", RoutePlanner.RuleSet.CLARIFIED_EXTERIOR_BOUNDARY_V1.name());
        value.put("supported_rulesets", Arrays.stream(RoutePlanner.RuleSet.values())
                .map(Enum::name).collect(Collectors.toList()));
        value.put("rule_profiles", Arrays.stream(RoutePlanner.RuleSet.values()).map(item -> {
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("id", item.name());
            profile.put("hash", item.getProfileHash());
            profile.put("experimental", item.isExperimental());
            profile.put("authority_reference", item.getAuthorityReference());
            profile.put("definition", item.getCanonicalDefinition());
            return profile;
        }).collect(Collectors.toList()));
        value.put("entry_strategies", Arrays.asList("AUTO", "DIRECT_ALLOWED", "PORTAL_ONLY"));
        value.put("max_upload_bytes", 3L * 1024L * 1024L * 1024L);
        value.put("max_supported_download_bytes", 500L * 1024L * 1024L);
        value.put("portfolio_mode", "parallel_with_certified_incumbent");
        value.put("search_objective", "connected_targets_then_score");
        value.put("first_full_algorithm", AlgorithmId.FIRST_FULL.getExternalName());
        value.put("first_full_status_header", "X-Full-Connectivity-Status");
        value.put("first_full_statuses", Arrays.asList("FULL",
                "PARTIAL_ENTRY_BLOCKED_WITH_WITNESS",
                "PARTIAL_ENTRY_BUDGET_EXHAUSTED",
                "PARTIAL_ENTRY_CANDIDATES_EXHAUSTED"));
        value.put("portfolio_branches", Arrays.asList("B3", "B2-C-J", "R1", "R2", "X1", "X2"));
        value.put("certification", "internal_and_post_export");
        value.put("diagnostic_fallback_default", false);
        value.put("certification_header", "X-Solution-Certified");
        value.put("completion_header", "X-Solution-Complete");
        value.put("solution_status_header", "X-Solution-Status");
        value.put("solution_statuses", Arrays.asList("FULL", "PARTIAL", "NO_CERTIFIED_SOLUTION"));
        return value;
    }

    @ExceptionHandler(AlgorithmUnavailableException.class)
    public ResponseEntity<Map<String, String>> unavailable(AlgorithmUnavailableException exception) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", exception.getStatus());
        body.put("message", exception.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(NoCertifiedSolutionException.class)
    public ResponseEntity<Map<String, String>> noCertifiedSolution(
            NoCertifiedSolutionException exception) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "NO_CERTIFIED_SOLUTION");
        body.put("source", exception.getSource());
        body.put("message", exception.getMessage());
        body.put("diagnostic_fallback", "request again with diagnosticFallback=true to inspect it");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "BAD_REQUEST");
        body.put("message", exception.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException exception) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "PAYLOAD_TOO_LARGE");
        body.put("message", "input GeoJSON exceeds the 3 GB upload limit");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> invalidGeoJson(IOException exception) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "INVALID_GEOJSON");
        body.put("message", exception.getMessage() == null
                ? "unable to read input GeoJSON" : exception.getMessage());
        return ResponseEntity.badRequest().body(body);
    }
}
