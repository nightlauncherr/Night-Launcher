// Worker entry point. Only the default export lives here: the Workers runtime
// treats every named export of the entry module as an entry point. The logic
// (and what the tests import) is in lib.js.
import { handle, internalError } from "./lib.js";

export default {
  async fetch(request, env) {
    try {
      return await handle(request, env);
    } catch {
      return internalError();
    }
  },
};
