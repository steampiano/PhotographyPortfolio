"""Telegram bot that translates every English message to Korean and posts the
translation right after it.

Setup:
  1. Create a bot with @BotFather and copy the token.
  2. pip install -r requirements.txt
  3. TELEGRAM_BOT_TOKEN=123:abc python bot.py

For group chats, disable privacy mode in @BotFather (/setprivacy -> Disable)
so the bot can see every message, then add it to the group.
"""

import asyncio
import logging
import os

from deep_translator import GoogleTranslator
from telegram import Update
from telegram.ext import Application, ContextTypes, MessageHandler, filters

logging.basicConfig(
    format="%(asctime)s %(levelname)s %(name)s: %(message)s", level=logging.INFO
)
logging.getLogger("httpx").setLevel(logging.WARNING)
log = logging.getLogger("translator-bot")

translator = GoogleTranslator(source="en", target="ko")


def translate(text: str) -> str:
    # Google's free endpoint caps requests at ~5000 chars; Telegram caps
    # messages at 4096, so a single call is always enough.
    return translator.translate(text)


async def on_message(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    msg = update.effective_message
    text = msg.text or msg.caption
    if not text:
        return
    try:
        # deep-translator is blocking; run it off the event loop.
        korean = await asyncio.to_thread(translate, text)
    except Exception:
        log.exception("Translation failed")
        return
    if korean and korean.strip() != text.strip():
        await msg.reply_text(korean, quote=False)


def main() -> None:
    token = os.environ.get("TELEGRAM_BOT_TOKEN")
    if not token:
        raise SystemExit("Set the TELEGRAM_BOT_TOKEN environment variable.")
    app = Application.builder().token(token).build()
    app.add_handler(
        MessageHandler(
            (filters.TEXT | filters.CAPTION) & ~filters.COMMAND & ~filters.VIA_BOT,
            on_message,
        )
    )
    log.info("Bot running. Press Ctrl+C to stop.")
    app.run_polling(allowed_updates=Update.ALL_TYPES)


if __name__ == "__main__":
    main()
