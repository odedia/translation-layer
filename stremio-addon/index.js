const { addonBuilder, serveHTTP } = require('stremio-addon-sdk');
const fetch = require('node-fetch');

// Configuration - adjust these to your Translation Layer server
const TRANSLATION_LAYER_URL = process.env.TRANSLATION_LAYER_URL || 'http://localhost:8080';
const PORT = process.env.PORT || 7001;

// Add-on manifest
const manifest = {
    id: 'org.translationlayer.subtitles',
    version: '1.0.0',
    name: 'Translation Layer Subtitles',
    description: 'Automatically translates English subtitles to your preferred language using AI (Ollama or OpenAI)',
    logo: 'https://raw.githubusercontent.com/odedia/translation-layer/main/images/Dashboard.png',
    catalogs: [],
    resources: ['subtitles'],
    types: ['movie', 'series'],
    idPrefixes: ['tt'], // IMDB IDs
    behaviorHints: {
        configurable: false,
        configurationRequired: false
    }
};

const builder = new addonBuilder(manifest);

// Helper to search subtitles via Translation Layer
async function searchSubtitles(imdbId, type, season, episode) {
    try {
        // Build query params
        const params = new URLSearchParams({
            imdb_id: imdbId,
            languages: 'en', // We always fetch English and translate
        });

        if (type === 'series' && season && episode) {
            params.append('season_number', season);
            params.append('episode_number', episode);
        }

        const response = await fetch(`${TRANSLATION_LAYER_URL}/api/v1/subtitles?${params}`, {
            headers: {
                'Content-Type': 'application/json',
                'User-Agent': 'Stremio-TranslationLayer/1.0'
            }
        });

        if (!response.ok) {
            console.error(`Search failed: ${response.status}`);
            return [];
        }

        const data = await response.json();
        return data.data || [];
    } catch (error) {
        console.error('Error searching subtitles:', error.message);
        return [];
    }
}

// Helper to get download link via Translation Layer
async function getDownloadLink(fileId) {
    try {
        const response = await fetch(`${TRANSLATION_LAYER_URL}/api/v1/download`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'User-Agent': 'Stremio-TranslationLayer/1.0'
            },
            body: JSON.stringify({ file_id: fileId })
        });

        if (!response.ok) {
            console.error(`Download request failed: ${response.status}`);
            return null;
        }

        const data = await response.json();
        return data.link || null;
    } catch (error) {
        console.error('Error getting download link:', error.message);
        return null;
    }
}

// Subtitles handler
builder.defineSubtitlesHandler(async ({ type, id }) => {
    console.log(`Subtitle request: type=${type}, id=${id}`);

    // Parse the ID (format: tt1234567 or tt1234567:1:2 for series)
    const parts = id.split(':');
    const imdbId = parts[0];
    const season = parts[1] || null;
    const episode = parts[2] || null;

    // Search for subtitles
    const results = await searchSubtitles(imdbId, type, season, episode);

    if (!results.length) {
        console.log('No subtitles found');
        return { subtitles: [] };
    }

    // Convert results to Stremio format
    // Note: We're limiting to first 10 results to avoid overwhelming users
    const subtitles = [];

    for (const result of results.slice(0, 10)) {
        const attrs = result.attributes || {};
        const files = attrs.files || [];

        for (const file of files) {
            const fileId = file.file_id;
            if (!fileId) continue;

            // Build the download URL through Translation Layer
            // The Translation Layer will handle translation
            const downloadUrl = `${TRANSLATION_LAYER_URL}/api/v1/download/${fileId}/subtitle.srt`;

            subtitles.push({
                id: `translationlayer-${fileId}`,
                url: downloadUrl,
                lang: 'heb', // The translated language - this should match your Translation Layer config
                SubFileName: attrs.release || `Translated - ${file.file_name || 'subtitle.srt'}`,
            });
        }
    }

    console.log(`Returning ${subtitles.length} subtitles`);
    return { subtitles };
});

// Start the server
serveHTTP(builder.getInterface(), { port: PORT });
console.log(`
🎬 Translation Layer Stremio Add-on running!
   
   Local:    http://localhost:${PORT}/manifest.json
   
   To install in Stremio:
   1. Open Stremio
   2. Go to Add-ons → Community Add-ons
   3. Enter: http://localhost:${PORT}/manifest.json
   
   Make sure Translation Layer is running at: ${TRANSLATION_LAYER_URL}
`);
