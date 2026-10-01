"use strict";

const MAX_BYTES = 10 * 1024 * 1024;
const MAX_SAMPLES = 16000 * 300;

function readInput(data) {
  if (!data || !["audio/wav", "audio/mp4"].includes(data.mime) ||
      typeof data.audio !== "string" || data.audio.length > Math.ceil(MAX_BYTES / 3) * 4 ||
      data.audio.length % 4 !== 0 || /[^A-Za-z0-9+/=]/.test(data.audio)) {
    throw new Error("WAV 또는 M4A 파일 형식이 올바르지 않습니다.");
  }
  const bytes = Buffer.from(data.audio, "base64");
  if (bytes.toString("base64") !== data.audio) throw new Error("녹음 데이터 형식이 올바르지 않습니다.");
  if (bytes.length <= 44 || bytes.length > MAX_BYTES) throw new Error("10MB 이하의 녹음만 분석할 수 있습니다.");
  return bytes;
}

// The decoder supplies mono, 16 kHz signed PCM16. Never alter the user's original file.
function preparePcm(pcm) {
  const count = pcm.length / 2;
  if (!Number.isInteger(count) || count < 1 || count > MAX_SAMPLES) {
    throw new Error("5분 이하의 녹음만 분석할 수 있습니다.");
  }
  let peak = 0;
  for (let offset = 0; offset < pcm.length; offset += 2) peak = Math.max(peak, Math.abs(pcm.readInt16LE(offset)));
  // Reject digital silence/quantization noise locally; a model can hallucinate on silence.
  if (peak <= 2) return {silent: true};
  const gain = Math.min(1000, Math.max(1, 0.8 * 32767 / peak));
  const wav = Buffer.alloc(44 + pcm.length);
  wav.write("RIFF", 0); wav.writeUInt32LE(36 + pcm.length, 4); wav.write("WAVEfmt ", 8);
  wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22);
  wav.writeUInt32LE(16000, 24); wav.writeUInt32LE(32000, 28);
  wav.writeUInt16LE(2, 32); wav.writeUInt16LE(16, 34); wav.write("data", 36);
  wav.writeUInt32LE(pcm.length, 40);
  for (let offset = 0; offset < pcm.length; offset += 2) {
    wav.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(pcm.readInt16LE(offset) * gain))), 44 + offset);
  }
  return {silent: false, wav, gain};
}

function parseAnalysis(text) {
  const value = JSON.parse(text);
  const certainty = value.certainty;
  if (!["clear", "uncertain", "unknown"].includes(certainty)) throw new Error("Invalid certainty");
  for (const [field, limit] of [["sound", 80], ["evidence", 240], ["alternative", 160]]) {
    if (typeof value[field] !== "string" || !value[field].trim() || value[field].length > limit) throw new Error("Invalid analysis");
  }
  return {sound: certainty === "unknown" ? "판단 불가" : value.sound.trim(), certainty,
    evidence: value.evidence.trim(), alternative: value.alternative.trim()};
}

module.exports = {readInput, preparePcm, parseAnalysis};
