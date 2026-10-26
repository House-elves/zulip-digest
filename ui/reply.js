// A reply from the tab: the elf suggests one (Claude reads the conversation), you edit it, and Send goes through the
// desk's bar. The same panel in the Zulip and Mail tabs; each gives it how to suggest and how to send.
import { LitElement, html, css, shared, spinner, elf } from '/elf-kit/1/kit.js';

/** "$0.42" ("<$0.01" for less). */
function usd(n) {
    return n > 0 && n < 0.01 ? '<$0.01' : `$${Number(n).toFixed(2)}`;
}

/** A [placeholder] the suggestion left for something to look up. */
const PLACEHOLDER = /\[[^\]\n]{3,}\]/;
const REPO = /^[A-Za-z0-9][A-Za-z0-9-]{0,38}\/[A-Za-z0-9._-]{1,100}$/;

/**
 * <elf-reply .suggest=${() => elf.act('draft-reply', …)} .send=${(draft, text, options) => elf.act('send-reply', …)}
 *     .findOut=${(text, repo) => elf.act('find-out', …)}>
 *
 * suggest() answers {reply, where, cc, repo, repoMentioned, mine, cost} (mine: your repos); send() sends the text you wrote (cc shows when the "all" option is ticked). {@code options}: checkboxes for the
 * send ([{key, label, checked, hint}]), as Mail's Reply all; their values reach send(). findOut() starts an agent
 * that looks up the reply's [placeholders] in a repo (or, for "mine", the one of yours Claude picks), answering
 * {agent: {id}, repo}; its report (agents/<id>.json) has the reply filled in, and what it found.
 */
class ElfReply extends LitElement {

    static properties = {
        suggest: { attribute: false },
        send: { attribute: false },
        options: { attribute: false },
        findOut: { attribute: false },
        _repo: { state: true },      // where Find out looks
        _looking: { state: true },   // where Find out is looking: the repo, or "mine" while Claude picks one
        _agent: { state: true },     // its report: {id, status, lines, output, error, cost}
        _before: { state: true },    // the text before Find out filled it in, for Undo
        _askRepo: { state: true },   // Find out opened without a repo the suggestion named
        _draft: { state: true },     // what suggest() answered
        _text: { state: true },
        _picked: { state: true },    // option key -> checked
        _busy: { state: true },      // suggest, send
        _sent: { state: true },
        _error: { state: true },
    };

    static styles = [shared, css`
        :host { display: block; }
        .panel-r { display: grid; gap: 10px; padding: 14px 16px; margin-top: 10px; border: 1.5px solid var(--accent);
            border-radius: var(--r-panel); background: var(--surface); min-width: 0; }
        .to { font-size: 13.5px; color: var(--muted); margin: 0; overflow-wrap: anywhere; }
        .to b { color: var(--ink); font-weight: 600; }
        textarea { width: 100%; box-sizing: border-box; min-height: 9rem; resize: vertical; font: 14.5px/1.5 var(--font);
            padding: 10px 12px; border: 1px solid var(--line); border-radius: var(--r-control); background: var(--bg);
            color: var(--ink); }
        .acts { display: flex; flex-wrap: wrap; gap: 8px 12px; align-items: center; }
        label.opt { display: inline-flex; gap: 8px; align-items: center; font-size: 14px; cursor: pointer; }
        .note { font-size: 13px; color: var(--muted); margin: 0; }
        .error { margin: 0; }
        .find { display: grid; gap: 8px; padding: 10px 12px; border-radius: var(--r-control); background: var(--bg); }
        .find .acts input { font: 14px var(--font); padding: 5px 9px; border: 1px solid var(--line);
            border-radius: var(--r-control); background: var(--surface); color: var(--ink); width: 18rem; max-width: 100%; }
        .line { font-size: 13px; color: var(--muted); margin: 0; overflow-wrap: anywhere; }
        ul.found { margin: 0; padding-left: 18px; display: grid; gap: 6px; font-size: 13.5px; }
        ul.found .src { color: var(--muted); overflow-wrap: anywhere; }
    `];

    connectedCallback() {
        super.connectedCallback();
        this._picked = Object.fromEntries((this.options ?? []).map(o => [o.key, !!o.checked]));
        if (!this._draft) this._suggest();
        this._unwatch = elf.onChange(what => { if (what === 'agents' && this._agent) this._readAgent(); });
    }

    disconnectedCallback() {
        super.disconnectedCallback();
        this._unwatch?.();
    }

    /** Find out in the repo typed in, or with "mine" in whichever of your repos Claude picks. */
    async _findOut(where = this._repo.trim()) {
        this._error = null;
        this._looking = where;
        this._agent = { status: 'queued', lines: [] };
        try {
            const r = await this.findOut(this._text, where);
            this._looking = r.repo ?? where;
            this._agent = { id: r.agent.id, status: r.agent.status, lines: [] };
            this._readAgent();
        } catch (e) {
            this._agent = null;
            this._error = `Couldn't start the agent: ${e.message}`;
        }
    }

    async _readAgent() {
        const id = this._agent?.id;
        if (!id) return;
        const a = await elf.agent(id).catch(() => null);
        if (!a || this._agent?.id !== id) return;
        const was = this._agent.status;
        this._agent = a;
        if (a.status === 'done' && was !== 'done' && a.output?.reply) {
            this._before = this._text;
            this._text = a.output.reply;
        }
    }

    async _suggest() {
        this._busy = 'suggest';
        this._error = null;
        try {
            this._draft = await this.suggest();
            this._text = this._draft.reply ?? '';
            this._repo = this._draft.repo ?? this._repo ?? '';
            this._agent = null;
            this._before = null;
        } catch (e) {
            this._error = `Couldn't suggest a reply: ${e.message}`;
            if (this._text == null) this._text = '';
        } finally {
            this._busy = null;
        }
    }

    async _send() {
        this._busy = 'send';
        this._error = null;
        try {
            this._sent = await this.send(this._draft ?? {}, this._text, { ...this._picked });
            this.dispatchEvent(new CustomEvent('reply-sent', { detail: this._sent, bubbles: true, composed: true }));
        } catch (e) {
            // Cancel in the desk's bar sent nothing: the reply stays here to send later.
            if (!e.cancelled) this._error = `Not sent: ${e.message}`;
        } finally {
            this._busy = null;
        }
    }

    /**
     * Find out: while the reply has [placeholders], where to look and the button; then the agent's progress, and
     * what it found once it filled them in.
     */
    _findPart(busy) {
        if (!this.findOut || this._sent) return '';
        const a = this._agent;
        if (a && ['queued', 'running'].includes(a.status)) {
            const line = a.lines?.length ? a.lines[a.lines.length - 1] : 'Starting…';
            return html`<div class="find" role="status">
                <p class="note">${spinner} ${this._looking === 'mine' ? 'Picking which of your repos to look in…'
                    : html`Finding out in <b>${this._looking}</b>. This takes a few minutes.`}</p>
                <p class="line">${line}</p>
                <div class="acts"><button class="sm quiet" @click=${() => elf.cancelAgent(a.id).catch(() => {})}>Stop</button></div>
            </div>`;
        }
        const found = a?.status === 'done' ? a.output?.findings ?? [] : [];
        const open = PLACEHOLDER.test(this._text ?? '');
        const mine = this._draft?.mine ?? [];
        const inMine = mine.length ? html`<button class="sm" ?disabled=${busy}
            title="Claude picks the one of your repos likeliest to answer the [brackets], and an agent looks there; nothing is sent"
            @click=${() => this._findOut('mine')}>Find out in my repos</button>` : '';
        return html`
            ${a?.status === 'done' ? html`<div class="find">
                <p class="note">Filled in from <b>${this._looking}</b>${a.cost != null ? ` (AI ${usd(a.cost)})` : ''}. Check it before you send.
                    ${this._before != null ? html`<button class="sm quiet" @click=${() => { this._text = this._before; this._before = null; }}>Undo</button>` : ''}</p>
                ${found.length ? html`<ul class="found">${found.map(f => html`<li><b>${f.question}</b> ${f.answer}
                    ${f.source ? html`<span class="src">(${f.source})</span>` : ''}</li>`)}</ul>` : ''}
                ${a.output?.note ? html`<p class="line">${a.output.note}</p>` : ''}
            </div>` : ''}
            ${a?.status === 'failed' ? html`<p class="error" role="alert">The agent couldn't find out: ${a.error}</p>` : ''}
            ${open && !this._busy && !this._draft?.repo && !this._draft?.repoMentioned && !this._askRepo && !this._repo
                ? html`<div class="acts">${inMine}<button class="sm quiet" @click=${() => { this._askRepo = true; }}
                    title="An agent can look up the [brackets] in a repo's code and docs">Look it up in a repo…</button></div>`
            : open && !this._busy ? html`<div class="find">
                <p class="note">The [brackets] are things to look up. An agent can find them out in a repo's code and docs,
                    in the runner's sandbox, after a safety check of the conversation.</p>
                ${this._draft?.repoMentioned && !this._repo ? html`<p class="line">The conversation points at
                    <b>${this._draft.repoMentioned}</b>, which isn't one of your repos. Type it in if you want it looked at.</p>` : ''}
                <div class="acts">
                    <input aria-label="Repo to look in" placeholder="owner/repo" list="mine" .value=${this._repo ?? ''}
                        @input=${e => { this._repo = e.target.value; }}>
                    <datalist id="mine">${mine.map(m => html`<option value=${m}></option>`)}</datalist>
                    <button class="sm" ?disabled=${busy || !REPO.test((this._repo ?? '').trim())}
                        title="Starts an agent that reads the repo and fills in the [brackets]; nothing is sent"
                        @click=${() => this._findOut()}>Find out</button>
                    ${this._repo?.trim() ? '' : inMine}
                </div>
            </div>` : ''}`;
    }

    _close() {
        this.dispatchEvent(new CustomEvent('reply-closed', { bubbles: true, composed: true }));
    }

    render() {
        if (this._sent) {
            return html`<div class="panel-r" role="status"><p class="note">Sent${this._sent.where ? ` to ${this._sent.where}` : ''}.</p>
                <div class="acts"><button class="sm quiet" @click=${this._close}>Close</button></div></div>`;
        }
        const d = this._draft;
        const busy = !!this._busy;
        return html`<div class="panel-r" role="group" aria-label="Reply">
            ${d?.where ? html`<p class="to">To <b>${d.where}</b>${d.cc && this._picked?.all ? html`, cc <b>${d.cc}</b>` : ''}</p>` : ''}
            ${this._busy === 'suggest' && this._text == null
                ? html`<p class="note">${spinner} Reading the conversation and suggesting a reply. This takes a little while.</p>`
                : html`<textarea aria-label="Your reply" .value=${this._text ?? ''} ?disabled=${busy}
                    @input=${e => { this._text = e.target.value; }}></textarea>`}
            ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
            ${this._findPart(busy)}
            <div class="acts">
                <button class="sm primary" ?disabled=${busy || !this._text?.trim() || !d}
                    @click=${() => this._send()}>${this._busy === 'send' ? spinner : ''}Send</button>
                ${(this.options ?? []).map(o => html`<label class="opt" title=${o.hint ?? ''}><input type="checkbox" ?disabled=${busy}
                    .checked=${!!this._picked?.[o.key]} @change=${e => { this._picked = { ...this._picked, [o.key]: e.target.checked }; }}>
                    ${o.label}</label>`)}
                <button class="sm quiet" ?disabled=${busy} @click=${() => this._suggest()}>
                    ${this._busy === 'suggest' && this._text != null ? spinner : ''}Suggest again</button>
                <button class="sm quiet" ?disabled=${this._busy === 'send'} @click=${this._close}>Cancel</button>
            </div>
            ${d?.cost != null ? html`<p class="note" title="Claude Code's figure at API prices (on a subscription, not what you're billed)">
                Suggested by Claude (AI ${usd(d.cost)}). Nothing is sent until you click Send and confirm it.</p>` : ''}
        </div>`;
    }
}

customElements.define('elf-reply', ElfReply);
