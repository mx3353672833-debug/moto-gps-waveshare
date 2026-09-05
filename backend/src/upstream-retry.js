const TRANSIENT_HTTP_STATUSES = new Set([408, 500, 502, 503, 504]);

export function isTransientUpstreamStatus(status) {
  return TRANSIENT_HTTP_STATUSES.has(Number(status));
}

function abortReason(signal) {
  return signal?.reason ?? new DOMException("The operation was aborted", "AbortError");
}

function waitForBackoff(delayMs, signal) {
  if (signal?.aborted) return Promise.reject(abortReason(signal));
  if (delayMs <= 0) return Promise.resolve();

  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, delayMs);
    const onAbort = () => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", onAbort);
      reject(abortReason(signal));
    };
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

/**
 * Retries only idempotent upstream GET requests. The caller supplies one
 * operation-wide timeout signal, so retries and their short backoff never
 * extend the public request deadline.
 */
export async function fetchGetWithTransientRetry(
  fetchImpl,
  url,
  {
    headers,
    signal,
    maxAttempts = 3,
    retryDelayMs = 100,
  } = {},
) {
  const boundedAttempts = Math.max(1, Math.min(3, Math.trunc(Number(maxAttempts)) || 1));
  const boundedDelayMs = Math.max(0, Math.min(250, Number(retryDelayMs) || 0));

  for (let attempt = 1; attempt <= boundedAttempts; attempt += 1) {
    if (signal?.aborted) throw abortReason(signal);

    try {
      const response = await fetchImpl(url, {
        method: "GET",
        headers,
        signal,
      });
      if (!isTransientUpstreamStatus(response.status) || attempt === boundedAttempts) {
        return response;
      }
      // Do not retain an unread failed response body while opening the next
      // connection. Body cancellation is best-effort and never changes retry
      // classification.
      try {
        await response.body?.cancel();
      } catch {
        // Ignore cleanup failures; the retry still observes the shared deadline.
      }
    } catch (error) {
      // A client cancellation or the operation-wide deadline must terminate
      // immediately. Only transport failures that occur before that boundary
      // are safe to retry.
      if (signal?.aborted || attempt === boundedAttempts) throw error;
    }

    await waitForBackoff(boundedDelayMs * 2 ** (attempt - 1), signal);
  }

  throw new Error("unreachable upstream retry state");
}
