# English → Korean Telegram translator bot

Translates every English message (text or photo caption) in a chat to Korean
and posts the translation right after it.

## Setup

1. In Telegram, message **@BotFather** → `/newbot` → copy the token.
2. **For group chats:** in @BotFather send `/setprivacy`, pick your bot, choose
   **Disable** so it can read every message. Then add the bot to the group.
3. Install and run (Python 3.10+):

   ```bash
   cd telegram-translator-bot
   pip install -r requirements.txt
   TELEGRAM_BOT_TOKEN=123456:ABC... python bot.py
   ```

The bot keeps running until you stop it with Ctrl+C. To keep it running 24/7,
host it somewhere always-on (a small VPS, Railway, Fly.io, a Raspberry Pi, …).

## Notes

- Translation uses Google Translate's free endpoint via `deep-translator`,
  no API key needed.
- Commands (`/start` etc.) and messages from other bots are ignored.
- If a translation comes back the same as the original (for example, the
  message was already Korean), nothing is posted.
