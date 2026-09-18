package ru.moshackathon.heatnetwork.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.moshackathon.heatnetwork.service.TraceService;

import java.io.IOException;

@RestController
@RequestMapping("/api/trace")
public class TraceController {
    private final TraceService traceService;

    public TraceController(TraceService traceService) {
        this.traceService = traceService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> trace(@RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(traceService.trace(file.getInputStream()));
    }
}
