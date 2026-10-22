// A reply from the tab: the elf suggests one (Claude reads the conversation), you edit it, and Send goes through the
// desk's bar. The same panel in the Zulip and Mail tabs; each gives it how to suggest and how to send.
import { LitElement, html, css, shared, spinner, elf } from '/elf-kit/1/kit.js';

/** "$0.42" ("<$0.01" for less). */
function usd(n) {
    return n > 0 && n < 0.01 ? '<$0.01' : `$${Number(n).toFixed(2)}`;
}

/**
 * <elf-reply .suggest=${() => elf.act('draft-reply', …)} .send=${(draft, text, options) => elf.act('send-reply', …)}>
 *
 * suggest() answers {reply, where, cc, cost}; send() sends the text you wrote (cc shows when the "all" option is ticked). {@code options}: checkboxes for the
 * send ([{key, label, checked, hint}]), as Mail's Reply all; their values reach send().
 */
class ElfReply extends LitElement {

    static properties = {
        suggest: { attribute: false },
        send: { attribute: false },
        options: { attribute: false },
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
    `];

    connectedCallback() {
        super.connectedCallback();
        this._picked = Object.fromEntries((this.options ?? []).map(o => [o.key, !!o.checked]));
        if (!this._draft) this._suggest();
    }

    async _suggest() {
        this._busy = 'suggest';
        this._error = null;
        try {
            this._draft = await this.suggest();
            this._text = this._draft.reply ?? '';
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
