<img src="https://raw.githubusercontent.com/House-elves/elf-desk/main/docs/brand/mark.svg" width="72" alt="House Elves logo: a checkmark that ends in an elf's hat">

# zulip-digest

A House Elf that sums up your Zulip with [Claude Code](https://claude.com/claude-code), for its **Zulip** tab on [Elf Desk](https://github.com/House-elves/elf-desk).

Each run reads what was said in your streams and direct messages over the last day (and any older unread mentions), and writes a short digest of each topic. The tab lists the topics that involve you first (mentions, DMs, followed topics), then the ones with unread messages. **Done** on a topic marks it read in Zulip. Nothing else is marked read.

**Reply** on a topic (or on your direct messages: the latest conversation) asks Claude for a suggested reply from its latest messages. You edit it, and **Send** posts it there as you, once you confirm it in the desk's bar. Where it goes always comes from Zulip itself. Nothing else is ever sent.

Suggestions follow **How you write**, a short note at the top of Zulip's Settings: write it yourself, or **Learn from my Zulip messages** has Claude write it from your recent messages (your own words only, without what they quote). Your own messages in the conversation also show it how you write. It's kept in `~/.config/zulip-digest/style/`.

**Find out**: a suggestion leaves what it can't know as [questions in brackets]. When one of them is about code, Find out has an agent look it up in a repo's code and docs and fill it in, showing what it found and where (file and line); Undo puts the suggestion back. The repo is filled in only when it's one of yours (from the GitHub elf's settings); a repo the conversation points at is only shown, for you to type in. The agent is the runner's, in its sandbox, after the safety check of the conversation, and can only read the repo's files (Read, Grep, Glob, refused anywhere else): no shell, no web, no MCP, so nothing it reads can leave except in its answer, which you read. It needs runner 0.5.7 or later.

Claude reads other people's words here, so every Claude call (digests and replies) runs with no tools (built in or MCP), none of your Claude settings, and in an empty folder.

## Install

In Elf Desk, the **+** after the last tab: **Add** it, which adds the Zulip tab, which asks for your Zulip server, the email you sign in with and your API key (Zulip, Personal settings, Account and privacy). Once they're saved it reads Zulip for the first time. After that it runs when you click **Refresh** (or the dashboard's **Refresh everything**) and on weekdays at 07:00, which you can change in its settings.

The desk reads [`elf.yaml`](elf.yaml): its settings, commands, tab and the actions its tab may ask for (Done, a suggested reply, and Send, which asks you first).

## Prerequisites

- Java 21+ and [JBang](https://www.jbang.dev/)
- The [Claude Code CLI](https://claude.com/claude-code) (`claude`), logged in
- A Zulip API key

## By hand

```bash
jbang ZulipDigest.java            # read, sum up and save the digest
jbang ZulipDigest.java --dry-run  # the same, without saving
```

Settings are in `~/.config/zulip-digest/config` (only readable by you): `ZULIP_SERVER_1`, `ZULIP_EMAIL_1`, `ZULIP_API_KEY_1` and `HOURS` (24). More servers can be added by hand as `ZULIP_SERVER_2` and so on. Digests are saved in `~/.config/zulip-digest/runs` for 90 days, and what you marked Done in `done/zulip.json` for a month.
