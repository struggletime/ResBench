const DEFAULT_ENV_INFO = "openGauss";

function getApiBaseUrl() {
  if (typeof window !== 'undefined' && window.__APP_CONFIG__?.apiBaseUrl) {
    return window.__APP_CONFIG__.apiBaseUrl.replace(/\/+$/, '');
  }
  return '';
}

function buildUrl(path, params = {}) {
  const base = getApiBaseUrl() || window.location.origin;
  const url = new URL(path, base);
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") {
      url.searchParams.set(key, value);
    }
  });
  return url.toString();
}

export function getEnvInfo() {
  return import.meta.env.VITE_ENV_INFO || DEFAULT_ENV_INFO;
}

export async function request(path, options = {}) {
  const { method = "GET", params, body } = options;
  const response = await fetch(buildUrl(path, params), {
    method,
    headers: {
      "Content-Type": "application/json",
    },
    body: body ? JSON.stringify(body) : undefined,
  });

  let payload = null;
  try {
    payload = await response.json();
  } catch {
    throw new Error("Invalid response from server");
  }

  if (!response.ok) {
    throw new Error(payload?.msg || `Request failed: ${response.status}`);
  }

  if (payload && typeof payload === "object" && "code" in payload) {
    if (payload.code !== 200) {
      throw new Error(payload.msg || "Request failed");
    }
    return payload.data;
  }

  return payload;
}
