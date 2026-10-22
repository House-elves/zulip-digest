// How you write: the note a suggested reply follows, so it sounds like you. Shown at the top of the elf's Settings
// pop-up (its settings view). Learn has Claude write it from your own recent messages; you can edit it either way.
import { LitElement, html, css, shared, spinner, ago, elf } from '/elf-kit/1/kit.js';

/** "$0.42" ("<$0.01" for less). */
function usd(n) {
    return n > 0 && n < 0.01 ? '<$0.01' : `$${Number(n).toFixed(2)}`;
}

/** <elf-style what="sent emails">: the note, with Learn and Save. */
class ElfStyle extends LitElement {

    static properties = {
        what: {},                   // what it learns from: "sent emails", "Zulip messages"
        _note: { state: true },     // style/style.json: {text, from, at}, or null
        _text: { state: true },
        _busy: { state: true },     // learn, save
        _said: { state: true },
        _error: { state: true },
    };

    static styles = [shared, css`
        :host { display: block; }
        section { display: grid; gap: 10px; }
        h2 { font-size: 18px; font-weight: 600; margin: 0; }
        .hint { font-size: 14px; color: var(--muted); margin: 0; max-width: 44rem; }
        textarea { width: 100%; box-sizing: border-box; min-height: 10rem; resize: vertical; font: 14px/1.5 var(--font);
            padding: 10px 12px; border: 1px solid var(--line); border-radius: var(--r-control); background: var(--bg);
            color: var(--ink); }
        .acts { display: flex; flex-wrap: wrap; gap: 8px 12px; align-items: center; }
        .note { font-size: 13px; color: var(--muted); }
        .error { margin: 0; }
    `];

    async connectedCallback() {
        super.connectedCallback();
        try {
            this._note = await elf.readJson('style', 'style.json');
        } catch {
            this._note = null;
        }
        this._text = this._note?.text ?? '';
    }

    async _do(busy, call) {
        this._busy = busy;
        this._error = null;
        this._said = null;
        try {
            this._note = await call();
            this._text = this._note.text ?? '';
            this._said = busy === 'learn'
                ? `Learnt from ${this._note.from}${this._note.cost != null ? ` (AI ${usd(this._note.cost)})` : ''}. Edit it if it's off, then Save.`
                : 'Saved. Suggested replies follow it from now on.';
        } catch (e) {
            this._error = e.message;
        } finally {
            this._busy = null;
        }
    }

    render() {
        const n = this._note;
        const changed = (this._text ?? '') !== (n?.text ?? '');
        return html`<section aria-labelledby="style-h">
            <h2 id="style-h">How you write</h2>
            <p class="hint">Suggested replies follow this, so they sound like you. Learn has Claude read your recent
                ${this.what ?? 'messages'} (only your own words) and write it; you can also write or change it yourself.</p>
            <textarea aria-label="How you write" .value=${this._text ?? ''} ?disabled=${!!this._busy}
                placeholder="Short and direct. Open with “Hi Name,”. Sign off with “Thanks”. No emoji."
                @input=${e => { this._text = e.target.value; this._said = null; }}></textarea>
            ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
            <div class="acts">
                <button class="sm primary" ?disabled=${!!this._busy || !changed}
                    @click=${() => this._do('save', () => elf.act('save-style', { text: this._text ?? '' }))}>
                    ${this._busy === 'save' ? spinner : ''}Save</button>
                <button class="sm" ?disabled=${!!this._busy}
                    title="Reads your recent ${this.what ?? 'messages'} and replaces what's above with what it learns"
                    @click=${() => this._do('learn', () => elf.act('learn-style', {}))}>
                    ${this._busy === 'learn' ? html`${spinner} Reading your ${this.what ?? 'messages'}…` : `Learn from my ${this.what ?? 'messages'}`}</button>
                ${this._said ? html`<span class="note" role="status">${this._said}</span>`
                    : n?.at ? html`<span class="note">${n.from === 'you' ? 'Written by you' : `Learnt from ${n.from}`}, ${ago(n.at)}.</span>` : ''}
            </div>
        </section>`;
    }
}

customElements.define('elf-style', ElfStyle);
