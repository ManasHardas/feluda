# 0013 Invitation token in the URL fragment

**Decision.** Invitation links are `https://<host>/join#t=<token>`. The page's script reads the
fragment, removes it from the address bar, and posts the token to the API.

**Why.** Browsers and link-preview crawlers (WhatsApp fetches a preview of every link) do not send
the fragment to the server, so the token stays out of server logs, proxy logs and referrer headers,
and a preview fetch cannot consume or expose it. Tokens are 256 random bits, single-use, expiring,
revocable, stored only as a SHA-256 hash, and five wrong address attempts per hour lock an
invitation.

**Limit.** The token is still visible to anyone who can read the WhatsApp message. That is why the
resident must also give an address on the approved list, and why an organizer can bind an
invitation to one household.
