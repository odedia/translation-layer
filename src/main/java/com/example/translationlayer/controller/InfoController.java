package com.example.translationlayer.controller;

import com.example.translationlayer.model.LanguagesResponse;
import com.example.translationlayer.model.LanguagesResponse.LanguageData;
import com.example.translationlayer.model.UserInfoResponse;
import com.example.translationlayer.model.UserInfoResponse.UserData;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Info endpoints matching OpenSubtitles API.
 */
@RestController
@RequestMapping("/api/v1/infos")
public class InfoController {

    /**
     * GET /api/v1/infos/user - Get user information.
     */
    @GetMapping("/user")
    public ResponseEntity<UserInfoResponse> getUserInfo(
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        UserData userData = new UserData(
                1000, // allowed_downloads
                1000, // allowed_translations
                "translator", // level
                1, // user_id
                false, // ext_installed
                true, // vip
                0, // downloads_count
                1000 // remaining_downloads
        );

        return ResponseEntity.ok(new UserInfoResponse(userData));
    }

    /**
     * GET /api/v1/infos/languages - Get supported languages.
     */
    @GetMapping("/languages")
    public ResponseEntity<LanguagesResponse> getLanguages() {
        List<LanguageData> languages = List.of(
                new LanguageData("en", "English"),
                new LanguageData("he", "Hebrew"));

        return ResponseEntity.ok(new LanguagesResponse(languages));
    }

    /**
     * GET /api/v1/infos/formats - Get supported subtitle formats.
     */
    @GetMapping("/formats")
    public ResponseEntity<Map<String, Object>> getFormats() {
        return ResponseEntity.ok(Map.of(
                "data", List.of(
                        Map.of("format_name", "SubRip", "format_extension", "srt"),
                        Map.of("format_name", "WebVTT", "format_extension", "vtt"))));
    }
}
