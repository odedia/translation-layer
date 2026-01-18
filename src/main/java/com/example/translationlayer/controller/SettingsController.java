package com.example.translationlayer.controller;

import com.example.translationlayer.config.AppSettings;
import com.example.translationlayer.config.LanguageConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller for application settings management.
 * Provides REST API and web UI for configuring all app settings.
 */
@Controller
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final AppSettings appSettings;
    private final LanguageConfig languageConfig;

    public SettingsController(AppSettings appSettings, LanguageConfig languageConfig) {
        this.appSettings = appSettings;
        this.languageConfig = languageConfig;
    }

    // ==================== REST API ====================

    @GetMapping("/api/settings")
    @ResponseBody
    public ResponseEntity<?> getSettings() {
        Map<String, Object> settings = new HashMap<>();

        // Mask sensitive keys for display
        settings.put("openSubtitlesApiKey", maskKey(appSettings.getOpenSubtitlesApiKey()));
        settings.put("openSubtitlesUsername", appSettings.getOpenSubtitlesUsername());
        // Don't expose password, just show if configured
        settings.put("openSubtitlesPasswordSet", !appSettings.getOpenSubtitlesPassword().isBlank());
        settings.put("openAiApiKey", maskKey(appSettings.getOpenAiApiKey()));
        settings.put("modelProvider", appSettings.getModelProvider());
        settings.put("ollamaModel", appSettings.getOllamaModel());
        settings.put("openAiModel", appSettings.getOpenAiModel());
        settings.put("ollamaBaseUrl", appSettings.getOllamaBaseUrl());
        settings.put("targetLanguage", appSettings.getTargetLanguage());
        settings.put("skipHearingImpaired", appSettings.isSkipHearingImpaired());
        settings.put("translationBatchSize", appSettings.getTranslationBatchSize());
        settings.put("smbHost", appSettings.getSmbHost());
        settings.put("smbShare", appSettings.getSmbShare());
        settings.put("smbUsername", appSettings.getSmbUsername());
        settings.put("smbDomain", appSettings.getSmbDomain());
        // Don't expose password
        settings.put("smbConfigured", !appSettings.getSmbHost().isBlank());

        return ResponseEntity.ok(settings);
    }

    @PostMapping("/api/settings")
    @ResponseBody
    public ResponseEntity<?> updateSettings(@RequestBody Map<String, Object> updates) {
        try {
            if (updates.containsKey("openSubtitlesApiKey")) {
                String key = updates.get("openSubtitlesApiKey").toString();
                if (!key.contains("***")) { // Don't update if masked
                    appSettings.setOpenSubtitlesApiKey(key);
                }
            }
            if (updates.containsKey("openSubtitlesUsername")) {
                appSettings.setOpenSubtitlesUsername(updates.get("openSubtitlesUsername").toString());
            }
            if (updates.containsKey("openSubtitlesPassword")) {
                String pwd = updates.get("openSubtitlesPassword").toString();
                if (!pwd.isEmpty()) { // Only update if provided
                    appSettings.setOpenSubtitlesPassword(pwd);
                }
            }
            if (updates.containsKey("openAiApiKey")) {
                String key = updates.get("openAiApiKey").toString();
                if (!key.contains("***")) {
                    appSettings.setOpenAiApiKey(key);
                }
            }
            if (updates.containsKey("modelProvider")) {
                appSettings.setModelProvider(updates.get("modelProvider").toString());
            }
            if (updates.containsKey("ollamaModel")) {
                appSettings.setOllamaModel(updates.get("ollamaModel").toString());
            }
            if (updates.containsKey("openAiModel")) {
                appSettings.setOpenAiModel(updates.get("openAiModel").toString());
            }
            if (updates.containsKey("ollamaBaseUrl")) {
                appSettings.setOllamaBaseUrl(updates.get("ollamaBaseUrl").toString());
            }
            if (updates.containsKey("targetLanguage")) {
                String lang = updates.get("targetLanguage").toString();
                appSettings.setTargetLanguage(lang);
                languageConfig.setTargetLanguage(lang);
            }
            if (updates.containsKey("skipHearingImpaired")) {
                appSettings.setSkipHearingImpaired(Boolean.parseBoolean(updates.get("skipHearingImpaired").toString()));
            }
            if (updates.containsKey("translationBatchSize")) {
                appSettings.setTranslationBatchSize(Integer.parseInt(updates.get("translationBatchSize").toString()));
            }
            if (updates.containsKey("smbHost")) {
                appSettings.setSmbHost(updates.get("smbHost").toString());
            }
            if (updates.containsKey("smbShare")) {
                appSettings.setSmbShare(updates.get("smbShare").toString());
            }
            if (updates.containsKey("smbUsername")) {
                appSettings.setSmbUsername(updates.get("smbUsername").toString());
            }
            if (updates.containsKey("smbPassword")) {
                String pwd = updates.get("smbPassword").toString();
                if (!pwd.isEmpty()) {
                    appSettings.setSmbPassword(pwd);
                }
            }
            if (updates.containsKey("smbDomain")) {
                appSettings.setSmbDomain(updates.get("smbDomain").toString());
            }

            appSettings.saveSettings();
            return ResponseEntity.ok(Map.of("success", true, "message", "Settings saved"));
        } catch (Exception e) {
            log.error("Failed to update settings", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/settings/ollama/models")
    @ResponseBody
    public ResponseEntity<?> getOllamaModels() {
        try {
            String baseUrl = appSettings.getOllamaBaseUrl();
            URL url = new URL(baseUrl + "/api/tags");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                // Parse models from response
                List<String> models = parseOllamaModels(response.toString());
                return ResponseEntity.ok(Map.of("models", models, "available", true));
            } else {
                return ResponseEntity.ok(Map.of("models", List.of(), "available", false,
                        "message", "Ollama not responding"));
            }
        } catch (Exception e) {
            log.error("Failed to get Ollama models", e);
            return ResponseEntity.ok(Map.of("models", List.of(), "available", false,
                    "message", "Ollama not available: " + e.getMessage()));
        }
    }

    private List<String> parseOllamaModels(String json) {
        List<String> models = new ArrayList<>();
        // Simple JSON parsing for models array
        int modelsStart = json.indexOf("\"models\"");
        if (modelsStart < 0)
            return models;

        int arrayStart = json.indexOf("[", modelsStart);
        int arrayEnd = json.indexOf("]", arrayStart);
        if (arrayStart < 0 || arrayEnd < 0)
            return models;

        String modelsArray = json.substring(arrayStart, arrayEnd + 1);

        // Find all "name" values
        int pos = 0;
        while (true) {
            int nameStart = modelsArray.indexOf("\"name\"", pos);
            if (nameStart < 0)
                break;

            int colonPos = modelsArray.indexOf(":", nameStart);
            int valueStart = modelsArray.indexOf("\"", colonPos) + 1;
            int valueEnd = modelsArray.indexOf("\"", valueStart);

            if (valueStart > 0 && valueEnd > valueStart) {
                models.add(modelsArray.substring(valueStart, valueEnd));
            }
            pos = valueEnd + 1;
        }

        return models;
    }

    // Model pull progress tracking
    private volatile String pullStatus = "";
    private volatile int pullProgress = 0;
    private volatile boolean pullInProgress = false;

    @PostMapping("/api/settings/ollama/pull")
    @ResponseBody
    public ResponseEntity<?> pullOllamaModel(@RequestBody Map<String, String> request) {
        String modelName = request.get("model");
        if (modelName == null || modelName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Model name required"));
        }

        if (pullInProgress) {
            return ResponseEntity.badRequest().body(Map.of("error", "Pull already in progress"));
        }

        // Start pull in background
        pullInProgress = true;
        pullStatus = "Starting pull...";
        pullProgress = 0;

        new Thread(() -> {
            try {
                String baseUrl = appSettings.getOllamaBaseUrl();
                java.net.URI uri = java.net.URI.create(baseUrl + "/api/pull");
                HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(0); // No timeout for streaming

                String payload = "{\"name\":\"" + modelName + "\"}";
                conn.getOutputStream().write(payload.getBytes());

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    // Parse progress from streaming response
                    if (line.contains("\"status\"")) {
                        int statusStart = line.indexOf("\"status\"") + 10;
                        int statusEnd = line.indexOf("\"", statusStart);
                        if (statusEnd > statusStart) {
                            pullStatus = line.substring(statusStart, statusEnd);
                        }
                    }
                    if (line.contains("\"completed\"") && line.contains("\"total\"")) {
                        try {
                            int completedStart = line.indexOf("\"completed\"") + 12;
                            int completedEnd = line.indexOf(",", completedStart);
                            if (completedEnd < 0)
                                completedEnd = line.indexOf("}", completedStart);
                            long completed = Long.parseLong(line.substring(completedStart, completedEnd).trim());

                            int totalStart = line.indexOf("\"total\"") + 8;
                            int totalEnd = line.indexOf(",", totalStart);
                            if (totalEnd < 0)
                                totalEnd = line.indexOf("}", totalStart);
                            long total = Long.parseLong(line.substring(totalStart, totalEnd).trim());

                            if (total > 0) {
                                pullProgress = (int) ((completed * 100) / total);
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                reader.close();
                pullStatus = "Complete!";
                pullProgress = 100;
            } catch (Exception e) {
                log.error("Failed to pull model", e);
                pullStatus = "Error: " + e.getMessage();
            } finally {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                }
                pullInProgress = false;
            }
        }).start();

        return ResponseEntity.ok(Map.of("success", true, "message", "Pull started"));
    }

    @GetMapping("/api/settings/ollama/pull/status")
    @ResponseBody
    public ResponseEntity<?> getPullStatus() {
        return ResponseEntity.ok(Map.of(
                "inProgress", pullInProgress,
                "status", pullStatus,
                "progress", pullProgress));
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 8)
            return key;
        return key.substring(0, 4) + "***" + key.substring(key.length() - 4);
    }

    // ==================== Settings Page UI ====================

    @GetMapping("/settings")
    @ResponseBody
    public String settingsPage() {
        StringBuilder html = new StringBuilder();

        html.append("<!DOCTYPE html><html><head>");
        html.append("<title>Translation Layer - Settings</title>");
        html.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        html.append("<style>");
        html.append("*{box-sizing:border-box}");
        html.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;");
        html.append(
                "background:linear-gradient(135deg,#1a1a2e 0%,#16213e 100%);color:#eee;margin:0;padding:16px;min-height:100vh}");
        html.append(".container{max-width:600px;margin:0 auto}");
        html.append("h1{color:#00d9ff;text-align:center;margin-bottom:8px;font-size:1.5em}");
        html.append(".nav{text-align:center;margin-bottom:20px}");
        html.append(".nav a{color:#00d9ff;text-decoration:none}");
        html.append(".card{background:rgba(255,255,255,0.1);border-radius:12px;padding:16px;margin-bottom:16px}");
        html.append(".card h2{margin-top:0;color:#00d9ff;font-size:1.1em;margin-bottom:12px}");
        html.append(".form-group{margin-bottom:12px}");
        html.append(".form-group label{display:block;margin-bottom:4px;color:#888;font-size:0.85em}");
        html.append(
                ".form-group input,.form-group select{width:100%;padding:10px;border-radius:6px;border:1px solid rgba(255,255,255,0.2);");
        html.append("background:rgba(0,0,0,0.3);color:#eee;font-size:14px}");
        html.append(".form-group input:focus,.form-group select:focus{outline:none;border-color:#00d9ff}");
        html.append(".toggle-row{display:flex;justify-content:space-between;align-items:center;padding:8px 0}");
        html.append(".toggle{position:relative;width:50px;height:26px}");
        html.append(".toggle input{opacity:0;width:0;height:0}");
        html.append(".toggle-slider{position:absolute;cursor:pointer;top:0;left:0;right:0;bottom:0;");
        html.append("background:#333;transition:.3s;border-radius:26px}");
        html.append(".toggle-slider:before{position:absolute;content:'';height:20px;width:20px;left:3px;bottom:3px;");
        html.append("background:#fff;transition:.3s;border-radius:50%}");
        html.append(".toggle input:checked+.toggle-slider{background:#00d9ff}");
        html.append(".toggle input:checked+.toggle-slider:before{transform:translateX(24px)}");
        html.append(
                ".btn{display:block;width:100%;padding:12px;border-radius:8px;font-weight:bold;border:none;cursor:pointer;font-size:14px;margin-top:12px}");
        html.append(".btn-primary{background:#00d9ff;color:#1a1a2e}");
        html.append(".btn-secondary{background:rgba(255,255,255,0.2);color:#eee}");
        html.append(".status-msg{color:#00ff88;font-size:0.85em;margin-top:8px}");
        html.append(".error-msg{color:#ff4757;font-size:0.85em;margin-top:8px}");
        html.append(".model-list{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px}");
        html.append(
                ".model-tag{background:rgba(0,217,255,0.2);padding:4px 8px;border-radius:4px;font-size:0.8em;cursor:pointer}");
        html.append(".model-tag:hover{background:rgba(0,217,255,0.4)}");
        html.append(".model-tag.active{background:#00d9ff;color:#1a1a2e}");
        html.append("</style></head><body>");

        html.append("<div class='container'>");
        html.append("<h1>⚙️ Settings</h1>");
        html.append("<p class='nav'><a href='/'>← Back to Status</a> | <a href='/browse'>File Browser</a></p>");

        // Model Provider Section
        html.append("<div class='card'><h2>🤖 AI Model</h2>");
        html.append("<div class='form-group'><label>Provider</label>");
        html.append("<select id='modelProvider' onchange='toggleProvider()'>");
        html.append("<option value='ollama'>Ollama (Local)</option>");
        html.append("<option value='openai'>OpenAI</option>");
        html.append("</select></div>");

        // Ollama settings
        html.append("<div id='ollamaSettings'>");
        html.append("<div class='form-group'><label>Ollama URL</label>");
        html.append("<input type='text' id='ollamaBaseUrl' placeholder='http://localhost:11434'></div>");
        html.append("<div class='form-group'><label>Model</label>");
        html.append("<input type='text' id='ollamaModel' placeholder='translategema2:4b'></div>");
        html.append("<div id='modelList' class='model-list'></div>");
        html.append("<button class='btn btn-secondary' onclick='refreshModels()'>Refresh Models</button>");
        // Pull new model section
        html.append("<div style='margin-top:16px;padding-top:16px;border-top:1px solid rgba(255,255,255,0.1)'>");
        html.append("<div class='form-group'><label>Pull New Model</label>");
        html.append("<div style='display:flex;gap:8px'>");
        html.append("<input type='text' id='pullModelName' placeholder='e.g. translategema:12b' style='flex:1'>");
        html.append(
                "<button class='btn btn-primary' onclick='pullModel()' style='width:auto;padding:10px 16px'>Pull</button>");
        html.append("</div></div>");
        html.append("<div id='pullProgress' style='display:none'>");
        html.append(
                "<div class='progress-bar' style='background:#333;border-radius:4px;height:20px;overflow:hidden;margin-top:8px'>");
        html.append(
                "<div id='pullProgressBar' style='background:linear-gradient(90deg,#00d9ff,#00ff88);height:100%;width:0%;transition:width 0.3s'></div></div>");
        html.append("<div id='pullStatus' style='text-align:center;color:#888;font-size:0.85em;margin-top:4px'></div>");
        html.append("</div></div>");
        html.append("</div>");

        // OpenAI settings
        html.append("<div id='openaiSettings' style='display:none'>");
        html.append("<div class='form-group'><label>OpenAI API Key</label>");
        html.append("<input type='password' id='openAiApiKey' placeholder='sk-...'></div>");
        html.append("<div class='form-group'><label>Model</label>");
        html.append("<select id='openAiModel'>");
        html.append("<option value='gpt-4o-mini'>GPT-4o Mini</option>");
        html.append("<option value='gpt-4o'>GPT-4o</option>");
        html.append("<option value='gpt-4-turbo'>GPT-4 Turbo</option>");
        html.append("</select></div>");
        html.append("</div>");
        html.append("</div>");

        // API Keys Section
        html.append("<div class='card'><h2>🔑 OpenSubtitles</h2>");
        html.append("<div class='form-group'><label>API Key</label>");
        html.append("<input type='password' id='openSubtitlesApiKey' placeholder='Enter API key'></div>");
        html.append("<div class='form-group'><label>Username</label>");
        html.append("<input type='text' id='openSubtitlesUsername' placeholder='Your OpenSubtitles username'></div>");
        html.append("<div class='form-group'><label>Password</label>");
        html.append(
                "<input type='password' id='openSubtitlesPassword' placeholder='Your OpenSubtitles password'></div>");
        html.append(
                "<p style='font-size:0.8em;color:#888'>Get your credentials from <a href='https://www.opensubtitles.com/consumers' target='_blank' style='color:#00d9ff'>opensubtitles.com</a></p>");
        html.append("</div>");

        // Translation Settings
        html.append("<div class='card'><h2>🌐 Translation</h2>");
        html.append("<div class='form-group'><label>Target Language</label>");
        html.append("<select id='targetLanguage'>");
        for (String lang : languageConfig.getSupportedLanguages().keySet()) {
            html.append("<option value='").append(lang).append("'>").append(lang).append("</option>");
        }
        html.append("</select></div>");
        html.append("<div class='toggle-row'><span>Skip hearing impaired text [brackets]</span>");
        html.append(
                "<label class='toggle'><input type='checkbox' id='skipHearingImpaired'><span class='toggle-slider'></span></label>");
        html.append("</div>");
        html.append("<div class='form-group'><label>Batch Size (cues per translation)</label>");
        html.append("<input type='number' id='translationBatchSize' min='1' max='50' value='20'>");
        html.append(
                "<p style='font-size:0.8em;color:#888;margin-top:4px'>Higher = better context, lower = faster feedback. Default: 20</p></div>");
        html.append("</div>");

        // SMB Settings
        html.append("<div class='card'><h2>📁 NAS Connection (SMB)</h2>");
        html.append("<div class='form-group'><label>Host</label>");
        html.append("<input type='text' id='smbHost' placeholder='192.168.1.100 or nas.local'></div>");
        html.append("<div class='form-group'><label>Share Name</label>");
        html.append("<input type='text' id='smbShare' placeholder='media'></div>");
        html.append("<div class='form-group'><label>Username (optional)</label>");
        html.append("<input type='text' id='smbUsername'></div>");
        html.append("<div class='form-group'><label>Password (optional)</label>");
        html.append("<input type='password' id='smbPassword'></div>");
        html.append("<div class='form-group'><label>Domain (optional)</label>");
        html.append("<input type='text' id='smbDomain'></div>");
        html.append("</div>");

        // Save Button
        html.append("<button class='btn btn-primary' onclick='saveSettings()'>💾 Save All Settings</button>");
        html.append("<div id='saveStatus'></div>");

        html.append("</div>");

        // JavaScript
        html.append("<script>");
        html.append("function loadSettings(){");
        html.append("fetch('/api/settings').then(r=>r.json()).then(s=>{");
        html.append("document.getElementById('modelProvider').value=s.modelProvider||'ollama';");
        html.append("document.getElementById('ollamaBaseUrl').value=s.ollamaBaseUrl||'';");
        html.append("document.getElementById('ollamaModel').value=s.ollamaModel||'';");
        html.append("document.getElementById('openAiApiKey').value=s.openAiApiKey||'';");
        html.append("document.getElementById('openAiModel').value=s.openAiModel||'gpt-4o-mini';");
        html.append("document.getElementById('openSubtitlesApiKey').value=s.openSubtitlesApiKey||'';");
        html.append("document.getElementById('openSubtitlesUsername').value=s.openSubtitlesUsername||'';");
        html.append(
                "if(s.openSubtitlesPasswordSet){document.getElementById('openSubtitlesPassword').placeholder='Password saved (leave blank to keep)';}");
        html.append("document.getElementById('targetLanguage').value=s.targetLanguage||'Hebrew (RTL)';");
        html.append("document.getElementById('skipHearingImpaired').checked=s.skipHearingImpaired||false;");
        html.append("document.getElementById('translationBatchSize').value=s.translationBatchSize||20;");
        html.append("document.getElementById('smbHost').value=s.smbHost||'';");
        html.append("document.getElementById('smbShare').value=s.smbShare||'';");
        html.append("document.getElementById('smbUsername').value=s.smbUsername||'';");
        html.append("document.getElementById('smbDomain').value=s.smbDomain||'';");
        html.append("toggleProvider();");
        html.append("refreshModels();");
        html.append("});");
        html.append("}");

        html.append("function toggleProvider(){");
        html.append("var p=document.getElementById('modelProvider').value;");
        html.append("document.getElementById('ollamaSettings').style.display=p==='ollama'?'block':'none';");
        html.append("document.getElementById('openaiSettings').style.display=p==='openai'?'block':'none';");
        html.append("}");

        html.append("function refreshModels(){");
        html.append("fetch('/api/settings/ollama/models').then(r=>r.json()).then(r=>{");
        html.append("var list=document.getElementById('modelList');");
        html.append("list.innerHTML='';");
        html.append(
                "if(!r.available){list.innerHTML='<span style=\"color:#888\">Ollama not available</span>';return;}");
        html.append("var current=document.getElementById('ollamaModel').value;");
        html.append("r.models.forEach(m=>{");
        html.append("var tag=document.createElement('span');");
        html.append("tag.className='model-tag'+(m===current?' active':'');");
        html.append("tag.textContent=m;");
        html.append("tag.onclick=function(){document.getElementById('ollamaModel').value=m;refreshModels();};");
        html.append("list.appendChild(tag);");
        html.append("});");
        html.append("});");
        html.append("}");

        // Pull model function
        html.append("var pullInterval=null;");
        html.append("function pullModel(){");
        html.append("var name=document.getElementById('pullModelName').value.trim();");
        html.append("if(!name){alert('Enter a model name');return;}");
        html.append("document.getElementById('pullProgress').style.display='block';");
        html.append("document.getElementById('pullStatus').textContent='Starting...';");
        html.append("document.getElementById('pullProgressBar').style.width='0%';");
        html.append(
                "fetch('/api/settings/ollama/pull',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({model:name})})");
        html.append(
                ".then(r=>r.json()).then(r=>{if(r.success){pollPullStatus();}else{document.getElementById('pullStatus').textContent='Error: '+r.error;}});");
        html.append("}");
        html.append("function pollPullStatus(){");
        html.append("if(pullInterval)clearInterval(pullInterval);");
        html.append("pullInterval=setInterval(()=>{");
        html.append("fetch('/api/settings/ollama/pull/status').then(r=>r.json()).then(r=>{");
        html.append("document.getElementById('pullProgressBar').style.width=r.progress+'%';");
        html.append("document.getElementById('pullStatus').textContent=r.status;");
        html.append(
                "if(!r.inProgress){clearInterval(pullInterval);pullInterval=null;setTimeout(()=>{refreshModels();document.getElementById('pullProgress').style.display='none';},2000);}");
        html.append("});");
        html.append("},1000);");
        html.append("}");

        html.append("function saveSettings(){");
        html.append("var data={");
        html.append("modelProvider:document.getElementById('modelProvider').value,");
        html.append("ollamaBaseUrl:document.getElementById('ollamaBaseUrl').value,");
        html.append("ollamaModel:document.getElementById('ollamaModel').value,");
        html.append("openAiApiKey:document.getElementById('openAiApiKey').value,");
        html.append("openAiModel:document.getElementById('openAiModel').value,");
        html.append("openSubtitlesApiKey:document.getElementById('openSubtitlesApiKey').value,");
        html.append("openSubtitlesUsername:document.getElementById('openSubtitlesUsername').value,");
        html.append("openSubtitlesPassword:document.getElementById('openSubtitlesPassword').value,");
        html.append("targetLanguage:document.getElementById('targetLanguage').value,");
        html.append("skipHearingImpaired:document.getElementById('skipHearingImpaired').checked,");
        html.append("translationBatchSize:parseInt(document.getElementById('translationBatchSize').value),");
        html.append("smbHost:document.getElementById('smbHost').value,");
        html.append("smbShare:document.getElementById('smbShare').value,");
        html.append("smbUsername:document.getElementById('smbUsername').value,");
        html.append("smbPassword:document.getElementById('smbPassword').value,");
        html.append("smbDomain:document.getElementById('smbDomain').value");
        html.append("};");
        html.append(
                "fetch('/api/settings',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)})");
        html.append(".then(r=>r.json()).then(r=>{");
        html.append("var status=document.getElementById('saveStatus');");
        html.append("if(r.success){status.innerHTML='<p class=\"status-msg\">✓ Settings saved!</p>';");
        html.append("}else{status.innerHTML='<p class=\"error-msg\">✗ '+r.error+'</p>';}");
        html.append("setTimeout(()=>{status.innerHTML='';},3000);");
        html.append("});");
        html.append("}");

        html.append("loadSettings();");
        html.append("</script>");

        html.append("</body></html>");

        return html.toString();
    }
}
