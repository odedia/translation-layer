package com.example.translationlayer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Login request matching OpenSubtitles API format.
 */
public record LoginRequest(
        @JsonProperty("username") String username,
        @JsonProperty("password") String password) {
}
