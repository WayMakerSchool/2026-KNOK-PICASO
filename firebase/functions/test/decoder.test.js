const {test} = require("node:test");
const assert = require("node:assert/strict");
const {mkdtemp, writeFile, readFile, rm} = require("node:fs/promises");
const {tmpdir} = require("node:os");
const {join} = require("node:path");
const execFile = require("node:util").promisify(require("node:child_process").execFile);
const {preparePcm} = require("../audio");
test("same decoder accepts ESP32 WAV and phone AAC M4A without truncating", async () => {
  const folder = await mkdtemp(join(tmpdir(), "knok-decoder-test-"));
  const ffmpeg = require("ffmpeg-static");
  try {
    const pcm = Buffer.alloc(32000);
    for (let i = 0; i < 16000; i++) pcm.writeInt16LE(Math.round(500 * Math.sin(i * 2 * Math.PI * 440 / 16000)), i * 2);
    const wav = join(folder, "tone.wav");
    const m4a = join(folder, "tone.m4a");
    await writeFile(wav, preparePcm(pcm).wav);
    await execFile(ffmpeg, ["-nostdin", "-loglevel", "error", "-i", wav, "-c:a", "aac", m4a]);
    for (const input of [wav, m4a]) {
      const output = join(folder, "output.pcm");
      await execFile(ffmpeg, ["-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-protocol_whitelist", "file",
        "-i", input, "-t", "301", "-vn", "-ac", "1", "-ar", "16000", "-f", "s16le", output]);
      const decoded = await readFile(output);
      assert.ok(decoded.length >= 32000 && decoded.length <= 35000);
      assert.equal(preparePcm(decoded).silent, false);
    }
  } finally { await rm(folder, {recursive: true, force: true}); }
});
