// Enable the API required by the registered Android debug provider (CommonJS).
// Uses the installed official Firebase CLI and its existing authorized account.
const {requireAuth} = require("../firebase/functions/node_modules/firebase-tools/lib/requireAuth");
const {ensure} = require("../firebase/functions/node_modules/firebase-tools/lib/ensureApiEnabled");
const {configstore} = require("../firebase/functions/node_modules/firebase-tools/lib/configstore");
(async () => {
  const user = configstore.get("user");
  const tokens = configstore.get("tokens");
  if (!user || !tokens) throw new Error("Sign in to the official Firebase CLI first.");
  await requireAuth({project: "knok-641b9", user, tokens, nonInteractive: true});
  await ensure("knok-641b9", "firebaseappcheck.googleapis.com", "sound-analysis");
  console.log("Firebase App Check API is enabled for knok-641b9.");
})().catch(error => { console.error(error.message); process.exitCode = 1; });
