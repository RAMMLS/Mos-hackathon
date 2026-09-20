package ru.moshackathon.heatnetwork.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class VisualizationPageController {
    @GetMapping({"/visualization", "/visualization/"})
    public String visualization() {
        return "redirect:/visualization/index.html";
    }
}
