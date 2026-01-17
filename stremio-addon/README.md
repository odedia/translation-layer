# Translation Layer Stremio Add-on

A Stremio add-on that connects to your Translation Layer service to provide AI-translated subtitles.

## Prerequisites

- Node.js 14 or later
- Translation Layer service running (default: `http://localhost:8080`)

## Installation

```bash
cd stremio-addon
npm install
```

## Running the Add-on

```bash
# Default configuration (Translation Layer at localhost:8080)
npm start

# Custom Translation Layer URL
TRANSLATION_LAYER_URL=http://192.168.1.10:8080 npm start

# Custom port
PORT=7002 npm start
```

## Adding to Stremio

1. Start the add-on: `npm start`
2. Open Stremio
3. Go to **Add-ons** → Click the search box
4. Enter: `http://localhost:7001/manifest.json`
5. Click **Install**

## How It Works

1. When you play a movie/series in Stremio, it requests subtitles from this add-on
2. The add-on queries Translation Layer for English subtitles from OpenSubtitles
3. When you select a subtitle, Stremio downloads it through Translation Layer
4. Translation Layer translates the subtitle to your configured language
5. You receive the translated subtitle

## Configuration

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `TRANSLATION_LAYER_URL` | `http://localhost:8080` | URL of your Translation Layer service |
| `PORT` | `7001` | Port for the Stremio add-on server |

## Note on First-Time Translations

Just like with Kodi, the first request for a subtitle will take time to translate. Stremio may show "loading" for a while. Once translated, the subtitle is cached and subsequent requests are instant.
