package ru.moshackathon.heatnetwork.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ViewerController {

    @GetMapping({"/viewer", "/viewer/"})
    public String viewer() {
        return "redirect:/viewer/index.html";
    }
}
