package ru.moshackathon.heatnetwork.api;

import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.moshackathon.heatnetwork.service.TraceService;
import ru.moshackathon.heatnetwork.solver.AlgorithmId;
import ru.moshackathon.heatnetwork.solver.AlgorithmUnavailableException;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/trace")
public class TraceController {
    private final TraceService traceService;

    public TraceController(TraceService traceService) {
        this.traceService = traceService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> trace(@RequestParam("file") MultipartFile file,
                                        @RequestParam(value = "algorithm", required = false) String algorithm)
            throws IOException {
        return ResponseEntity.ok(traceService.trace(file.getInputStream(), AlgorithmId.parse(algorithm)));
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

    @ExceptionHandler(AlgorithmUnavailableException.class)
    public ResponseEntity<Map<String, String>> unavailable(AlgorithmUnavailableException exception) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", exception.getStatus());
        body.put("message", exception.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "BAD_REQUEST");
        body.put("message", exception.getMessage());
        return ResponseEntity.badRequest().body(body);
    }
}
