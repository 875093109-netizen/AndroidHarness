# Telegram remote control

Telegram uses the same AndroidHarness chats, selected models, project folders, editing tools and permission settings as the phone interface.

## Connect

1. Create a bot with Telegram's BotFather.
2. Open **Settings → Telegram**, enter its token and verify the bot.
3. Tap **Pair account**, then **Open bot and pair**. Send the prepared `/start` message to the bot. You can also copy the `/pair` command. Codes expire after ten minutes.
4. Confirm the displayed account in AndroidHarness.
5. Choose a project, an existing or new chat, and a model. Enable Telegram.

The token is stored in the app's encrypted credentials. Only the confirmed private Telegram account can submit requests or use buttons. Pairing does not enable remote execution automatically.

## Work from Telegram

Send a normal message to continue the selected chat. The agent reads and edits that chat's assigned project, even when a different project is open on the phone. Tasks retain the destination and model chosen when accepted. Messages received during a task queue for the same conversation.

| Command | Action |
| --- | --- |
| `/help` | Show instructions and simple control buttons |
| `/projects` | Choose an app project and create a chat there |
| `/chats` | Choose an existing chat in the selected project |
| `/new [title]` | Start a fresh chat in the selected project |
| `/model` | Choose a connected provider and model |
| `/mode plan` / `/mode act` | Inspect and plan, or use editing tools |
| `/status` | Show project, chat, model, progress and queue |
| `/stop` | Stop the selected task and cancel its Telegram queue |
| `/retry [request ID]` or `/resume` | Retry an unstarted request or resume its saved task |
| `/pause` | Pause Telegram reception; resume from the phone |

Replies include progress and a final result. Tool permission requests offer **Approve once** and **Deny**. Agent questions offer answer buttons and accept typed answers; for multiple selections, send the selected options together. Buttons expire and are tied to the exact displayed task and operation. Workspace MCP connections that need initial approval must be approved in the phone app first.

Model changes follow the app's shared/per-chat preference and the selected Plan/Act model role. Stop queued work before changing its destination or model. The app's existing provider authentication and permission settings apply.

## Background operation and recovery

Enabling Telegram starts a visible Android foreground service. Its notification opens Telegram settings and offers **Pause** and **Stop**. The listener holds a CPU wake lock while enabled and reconnects after network failures. The phone must remain online; vendor battery restrictions and force-stopping the app can stop background reception. Reopen AndroidHarness to reconnect after a force stop.

Pause stops reception while an existing agent task can continue. Stop cancels the selected task and pauses Telegram. Disabling or unpairing retains ordinary app chat history and completed file changes.

Incoming requests and the Telegram cursor are saved together before acknowledgement. Accepted work survives reconnects. After process death, interrupted edits require an explicit retry/resume rather than silently repeating tools. A completed request can still deliver its saved result after the connection returns.

The **⋮** menu contains token, pairing, test-message and battery controls; **Help & commands** lists the bot commands. Failed or uncertain replies appear under **Saved work** with **Resend reply**. Resending only sends the saved reply; it does not rerun the model or tools. Check Telegram first when delivery is uncertain, because the previous send may have reached it. Telegram rate limits use the server's retry delay. A bot already used by a webhook or another polling listener needs that other receiver stopped before AndroidHarness can listen.

Tokens, paired identities, queued remote commands and remote delivery state are device-local and excluded from portable/system backups.

## Validation

Unit tests cover paired-account restrictions, durable reception, stale buttons, reconnects, interrupted delivery, rate limits and explicit recovery. Android tests exercise the production settings and a real foreground service with Room history, project file tools, checkpoints and exact-operation approvals. Their model and Telegram transport are fixtures.

`TelegramLiveSetupDeviceTest` is opt-in (`liveTelegramKilo=true`) and uses an already paired encrypted test connection. It checks the live Kilo free-model catalog, native project reads/writes and actual Telegram delivery while the activity is minimized. Incoming sample requests are controlled test inputs; this test does not consume the owner's actual Telegram inbox.
