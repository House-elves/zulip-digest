// The digests zulip-digest saved (~/.config/zulip-digest/runs, one JSON file each), as its tab reads them through
// the desk, and what you marked Done (~/.config/zulip-digest/done/zulip.json).
import { elf, runTime } from '/elf-kit/1/kit.js';

const ID = /^(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})\.json$/;

function atOf(file) {
    const m = ID.exec(file);
    return m ? new Date(+m[1], m[2] - 1, +m[3], +m[4], +m[5], +m[6]) : null;
}

/** Its runs, newest first: [{file, id, at, status, label}], up to 30. */
export async function runs() {
    const files = (await elf.list('runs')).filter(f => ID.test(f.name)).slice(0, 30).map(f => f.name);
    if (!files.length) return [];
    const heads = await elf.read('runs', files, ['status']);
    return files.filter(f => heads[f]).map(f => {
        const status = heads[f].status ?? 'success';
        return { file: f, id: f.slice(0, -5), at: atOf(f), status,
            label: `${runTime(atOf(f))}${status !== 'success' ? ` (${status})` : ''}` };
    });
}

/** A run by id (null: the newest), with its file's contents, or null. */
export async function run(list, id) {
    const r = (id && list.find(x => x.id === id)) || list[0];
    return r ? { ...r, data: await elf.readJson('runs', r.file) } : null;
}

/** What you marked Done: {server: {"stream > topic": when}}. */
export async function done() {
    try {
        return (await elf.readJson('done', 'zulip.json')) ?? {};
    } catch {
        return {};
    }
}
