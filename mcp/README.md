# KidGuard MCP server

Lets an AI assistant (Claude Desktop, Claude Code, any MCP client) read your child's KidGuard status and change rules.
One file, `kidguard-mcp.mjs`: Node >= 18, stdio, no dependencies, no install. It talks to the phone the way the parent page
does (same endpoints, `X-KG-Token`), and sends `X-KG-Name: AI assistant (MCP)`, so it shows in the phone's paired-devices list.

## Tools

| Tool | Does |
|---|---|
| `kid_status` | online / last seen, battery %, locked now, minutes used / left today, current app, location + last zone event, open requests |
| `usage_today` | minutes per app today (top 15) |
| `recent_events {limit?}` | newest events first: blocked apps, limits, zones, SOS, wrong PIN, requests, blocked calls |
| `guide {query?}` | searches the user guide (`website/guide.html`, else kidguard.asia) so the AI can explain what it cannot do itself |
| `get_rules` | current rules JSON (PIN hash hidden) |
| `update_rules {patch}` | JSON merge-patch (RFC 7396). Reads the current rules, applies the patch, PUTs the WHOLE object (rules are a full replace) |
| `grant_time {minutes}` | adds to today's bonus (1..240) |
| `answer_request {req, ok, min?}` | answers an open request like the page does (command + the rules edit) |
| `send_command {type, text?}` | only `lock locate message ring ringStop sosAck` |

Over the relay, commands and rules are queued: tools answer "queued", never "done". Check `kid_status` later.

## Safety: what the AI can and cannot do

- Can: read state and rules, change any rule except the PIN, give time, answer requests, lock, ring, locate, show a message.
- Cannot (refused in the server, never sent): `release`, `clearData`, `reboot`, `adbShell`, `adbTcp`, `unpair`, `unpairAll`,
  `relay`, `openPairing`, `pinSet`/`pinReset`, `tailscale`, any PIN-gated command, live screen, listen, files.
  `update_rules` rejects `pin`, `pinHash`, `pinSet`, `rev`.
- Rule changes are still powerful (it can remove limits or block apps): use it for things you would do on the page.
  The token is a paired-parent token: anyone with it has the same access. Revoke it from the page (paired devices list).
- Defence in depth: the PIN-gated commands are also gated there, so even a modified server cannot run them without the PIN.

## Get the id and token

Relay (works from anywhere): open the parent page, Settings, relay section. The `/go` link has everything:
`https://<relay>/go#c=<ID>&k=<TOKEN>&n=<name>`. `KG_RELAY` = the origin (`https://<relay>`), `KG_ID` = `c`, `KG_TOKEN` = `k`.
That token is shared by every relay parent (see protocol "relayShared"); renewing the relay link rotates it.

LAN (`KG_URL=http://<phone ip>:8770`): needs a LAN token. Gap: no screen shows a LAN token as text. The page keeps it in
browser storage after pairing (`kg.kids` in localStorage). A relay link token also works on the LAN
(it is one of the phone's LAN tokens), so prefer the relay link values with `KG_URL`.

## Config

**Use the AI token** (parent page: Settings > AI assistant > Create code; set it as `KG_AI_TOKEN`, no `KG_TOKEN`).
The phone and the relay worker enforce its limits, not only this server: it reads state, changes rules (never the PIN or a
protection switch), and may send only `lock locate message ring ringStop sosAck answer`. Everything else gets
`403 ai token: not allowed`, so a stolen config cannot release, wipe, reboot or re-pair the phone. Remove the code on the page to cut it.
`KG_TOKEN` (a paired parent token = full power) still works but is not recommended. Without a phone that knows the AI token
(older app), the page says so.

Claude Desktop (`claude_desktop_config.json`):

```json
{ "mcpServers": { "kidguard": {
  "command": "node",
  "args": ["/path/to/kidguard/mcp/kidguard-mcp.mjs"],
  "env": { "KG_RELAY": "https://your-relay.workers.dev", "KG_ID": "0123456789abcdef", "KG_TOKEN": "..." }
} } }
```

Claude Code:

```sh
claude mcp add kidguard -e KG_RELAY=https://your-relay.workers.dev -e KG_ID=0123456789abcdef -e KG_TOKEN=... -- node /path/to/kidguard/mcp/kidguard-mcp.mjs
```

LAN: replace the three relay vars with `KG_URL=http://192.168.1.20:8770` and `KG_TOKEN`.

## Test

`node mcp/test.mjs` starts the server against a fake relay and a fake LAN phone: all tools, the full-replace PUT body, forbidden
commands rejected. It does not touch a real phone.
