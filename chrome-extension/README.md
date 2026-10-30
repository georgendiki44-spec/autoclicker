# TikTok Comment Assistant (Chrome)

A Manifest V3 Chrome extension that helps prepare relevant TikTok comment drafts.

## Features
- Two exclusive modes: reusable comments or Gemini-generated drafts.
- Checks detected likes and comment counts against user-configured minimums.
- Inserts a reviewed draft into TikTok's comment editor.
- Keeps a simple session counter that the user can update manually in a future version.

## Safety and posting
This extension does **not** click TikTok's Post button, navigate through videos to post comments, or publish comments in the background. You review each draft and submit it yourself. TikTok's page structure changes often, so metric and comment-box detection may need selector updates.

## Install
1. Open `chrome://extensions`.
2. Enable **Developer mode**.
3. Select **Load unpacked**.
4. Choose this `chrome-extension` folder.
5. Open TikTok in a tab and configure the extension from its toolbar icon.

## Gemini
AI mode requires your own Gemini API key. It is stored in Chrome local extension storage and sent directly from your browser to Google's Generative Language API when you click **Prepare comment**. Google may charge for API use depending on your account and current pricing. Reusable-comment mode does not call an AI API.
