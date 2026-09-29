package ru.moshackathon.heatnetwork.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.moshackathon.heatnetwork.model.Solution;
import ru.moshackathon.heatnetwork.service.TraceService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceControllerCoverageHeaderTest {
    @Test
    void partialCertifiedResponseHasSeparateCertificationAndCompletionHeaders() throws Exception {
        TraceService service = mock(TraceService.class);
        Solution partial = new Solution("partial");
        partial.addUnconnected("cp-2", 1.0, "test");
        partial.setOutcome(3, true);
        when(service.solve(any(), any(), anyLong(), nullable(
                ru.moshackathon.heatnetwork.solver.RoutePlanner.EntryStrategy.class),
                any(), anyBoolean())).thenReturn(partial);
        TraceController controller = new TraceController(service);

        ResponseEntity<StreamingResponseBody> response = controller.trace(
                new MockMultipartFile("file", "input.geojson", "application/geo+json", "{}".getBytes()),
                "B3", 1_000, "AUTO", "DOCUMENT_NEAREST_V1", false);

        assertEquals("true", response.getHeaders().getFirst("X-Solution-Certified"));
        assertEquals("false", response.getHeaders().getFirst("X-Solution-Complete"));
        assertEquals("PARTIAL", response.getHeaders().getFirst("X-Solution-Status"));
        assertEquals("2/3", response.getHeaders().getFirst("X-Solution-Coverage"));
        assertEquals("DOCUMENT_NEAREST_V1", response.getHeaders().getFirst("X-Ruleset-Id"));
        assertEquals("false", response.getHeaders().getFirst("X-Ruleset-Experimental"));
        assertEquals("9d11fd5c124dee727569e4cb7f0aba9926d1c8d764f1c0c27a91075fa5b4ee32",
                response.getHeaders().getFirst("X-Ruleset-Hash"));
    }
}
