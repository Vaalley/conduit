# Chat markdown (issue #90)

Basic Discord-style markdown for what players type in chat. Anyone can use it (it is not the
Donator sign markdown, which is `%`-codes and lives in `text/Markdown.kt`).

| Type | Get |
|------|-----|
| `**bold**` | bold |
| `*italic*` | italic |
| `__underline__` | underline |
| `~~strike~~` | strikethrough |

- They nest: `***both***`, `**bold *and italic* text**`, `__**x**__`.
- A delimiter with no partner stays as typed. An opening delimiter must touch the word after it
  and a closing one the word before it (`2 * 3 * 4` is arithmetic), and `__` inside a word
  (`snake__case__name`) does nothing. Single `_` and `~` are plain characters.
- A backslash before `*`, `_`, `~` or `\` switches it off and is not shown: `\*expression*`
  reads `*expression*`. A backslash before anything else is a backslash.
- `http://` / `https://` links are underlined and clickable (`ClickEvent.OpenUrl`, hover shows
  the address). Markdown inside a link is ignored; markdown around one still applies.
  Trailing sentence punctuation and closing delimiters are not part of the link; balanced
  parentheses are. Only http/https with a host are links.

## Where it applies

Public chat, `/chat server`, `/chat <players>` and `/chat region` lines, and `/msg` / `/reply`.
Not the emotes, and not sign text.

## How

`ChatMarkdown.format(raw)` returns the styled component, or null when the text needs nothing
(no markdown, link or escape), so plain chat is sent exactly as typed. For public chat the
component is attached as the message's **unsigned content**: the signed text stays what the
player typed (chat reporting keeps working) and only what clients draw changes.

The Discord mirror (`ChatBridge`) is given the typed text with its markers, since Discord
renders its own markdown.
