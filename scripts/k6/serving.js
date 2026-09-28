import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Rate, Counter } from 'k6/metrics';

// One measured iteration issues exactly one HTTP request (no retries/redirects).
const base = (__ENV.BASE_URL || 'http://localhost:8081/api/wsbd').replace(/\/$/, '');
const profile = __ENV.PROFILE || 'smoke';
const profiles = {
  smoke: { rate: 2, duration: '30s' },
  baseline: { rate: 17, duration: '10m' },
  peak: { rate: 100, duration: '15m' },
  growth: { rate: 150, duration: '15m' },
  soak: { rate: 100, duration: '1h' },
  stress: { rate: 200, duration: '10m' },
};
if (!profiles[profile]) throw new Error(`Unknown PROFILE: ${profile}`);
function positiveInteger(name, fallback) {
  const value = Number(__ENV[name] || fallback);
  if (!Number.isSafeInteger(value) || value < 1) throw new Error(`${name} must be a positive integer`);
  return value;
}
const rate = positiveInteger('RPS', profiles[profile].rate);
const duration = __ENV.DURATION || profiles[profile].duration;
const preAllocatedVUs = positiveInteger('PRE_ALLOCATED_VUS', Math.max(10, rate * 2));
const maxVUs = positiveInteger('MAX_VUS', Math.max(preAllocatedVUs, rate * 5));
if (maxVUs < preAllocatedVUs) throw new Error('MAX_VUS must be >= PRE_ALLOCATED_VUS');
const failures = new Rate('api_failures');
const requests = new Counter('api_requests');
const endpoints = ['list', 'detail', 'recent_count', 'regions'];
const thresholds = {
  'http_req_failed{phase:load}': ['rate<0.01'],
  api_failures: ['rate<0.01'],
  dropped_iterations: ['count==0'],
};
for (const endpoint of endpoints) {
  thresholds[`http_req_duration{phase:load,endpoint:${endpoint}}`] = ['p(95)<500', 'p(99)<1000'];
  thresholds[`api_failures{endpoint:${endpoint}}`] = ['rate<0.01'];
  thresholds[`api_requests{endpoint:${endpoint}}`] = ['count>0'];
}
export const options = {
  scenarios: {
    traffic: { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration,
      preAllocatedVUs, maxVUs, gracefulStop: '15s' },
  },
  thresholds,
  setupTimeout: '2m',
  // Avoid per-id / cursor URL cardinality. Keep TLS validation enabled.
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response', 'error_code'],
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};
const headers = __ENV.BEARER_TOKEN ? { Authorization: `Bearer ${__ENV.BEARER_TOKEN}` } : {};
function get(path, endpoint, phase) {
  return http.get(`${base}/v1${path}`, {
    headers, timeout: '5s', redirects: 0,
    tags: { name: endpoint, endpoint, phase },
  });
}
function json(response) {
  try { return response.json(); } catch (_) { return null; }
}
function query(region) {
  return Object.keys(region).map(key => `${key}=${encodeURIComponent(region[key])}`).join('&');
}
function listPath(region, cursor, limit = 20) {
  return `/welfare-policies?limit=${limit}&${query(region)}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`;
}
function validList(body) {
  return body && Array.isArray(body.items) && typeof body.hasNext === 'boolean'
    && body.items.every(item => item.id != null && typeof item.serviceName === 'string')
    && (!body.hasNext || (typeof body.nextCursor === 'string' && body.nextCursor.length > 0));
}
export function setup() {
  const response = get('/regions', 'regions', 'setup');
  const catalog = json(response);
  if (response.status !== 200 || !catalog || !Array.isArray(catalog.sido) || !catalog.sido.length) {
    throw new Error('Region catalog preflight failed. Check BASE_URL, access and server.');
  }
  const provinces = catalog.sido.map(s => ({ sido: s.sidoName }));
  const districts = [];
  for (const province of catalog.sido) {
    for (const district of province.sigungu) {
      districts.push({ sido: province.sidoName, sigungu: district.sigunguName });
    }
  }
  if (!districts.length) throw new Error('Region catalog has no districts');
  const ids = [];
  let cursor = null;
  const pages = positiveInteger('SEED_PAGES', 20);
  for (let page = 0; page < pages; page++) {
    const res = get(listPath({}, cursor, 100), 'list', 'setup');
    const body = json(res);
    if (res.status !== 200 || !validList(body)) throw new Error('Policy seed list preflight failed');
    for (const item of body.items) ids.push(String(item.id));
    if (!body.hasNext) break;
    cursor = body.nextCursor;
  }
  if (!ids.length) throw new Error('No visible published policies. Prepare representative test data first.');
  if (ids.length < 100) console.warn(`Only ${ids.length} detail IDs: repeated-query performance may be unrepresentatively high`);
  console.log(JSON.stringify({ profile, rate, duration, preAllocatedVUs, maxVUs, seedIds: ids.length }));
  return { provinces, districts, ids };
}
function pick(values) { return values[Math.floor(Math.random() * values.length)]; }
function region(data) {
  const n = Math.random();
  return n < 0.2 ? {} : n < 0.5 ? pick(data.provinces) : pick(data.districts);
}
// VU-local pagination follows real cursors with the same region, at most 5 pages.
let paging = null;
export default function (data) {
  const slot = exec.scenario.iterationInTest % 20;
  let endpoint, path, id;
  if (slot < 8) {
    endpoint = 'list';
    if (!paging || !paging.cursor || paging.pages >= 5 || Math.random() < 0.5) {
      paging = { region: region(data), cursor: null, pages: 0 };
    }
    path = listPath(paging.region, paging.cursor);
  } else if (slot < 16) {
    endpoint = 'detail';
    id = pick(data.ids);
    path = `/welfare-policies/${encodeURIComponent(id)}`;
  } else if (slot < 19) {
    endpoint = 'recent_count';
    path = `/welfare-policies/recent-updates/count?updateDays=7&${query(region(data))}`;
  } else {
    endpoint = 'regions'; path = '/regions';
  }
  const response = get(path, endpoint, 'load');
  const body = json(response);
  let valid = response.status === 200;
  if (endpoint === 'list') {
    valid = valid && validList(body);
    paging.cursor = valid && body.hasNext ? body.nextCursor : null;
    paging.pages++;
  } else if (endpoint === 'detail') {
    valid = valid && body && String(body.id) === id && typeof body.serviceName === 'string';
  } else if (endpoint === 'recent_count') {
    valid = valid && body && Number.isInteger(body.count) && body.count >= 0 && body.updateDays === 7;
  } else {
    valid = valid && body && Array.isArray(body.sido) && body.sido.length > 0;
  }
  check(response, { '200 with valid API body': () => Boolean(valid) }, { endpoint });
  failures.add(!valid, { endpoint });
  requests.add(1, { endpoint });
}
