#!/usr/bin/env node
/**
 * Test: 3 users try to reserve the SAME seat at the same time.
 * Expected: exactly 1 succeeds, 2 get "already booked" (409/400).
 *
 * Usage (backend must be running on 8080):
 *   node scripts/test-concurrent-booking.js
 *
 * Or with API base:
 *   API_BASE=http://localhost:8080/api node scripts/test-concurrent-booking.js
 */

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api';

async function request(method, path, body = null, userId = null) {
  const opts = {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(userId && { 'X-User-Id': userId }),
    },
  };
  if (body) opts.body = JSON.stringify(body);
  const res = await fetch(`${API_BASE}${path}`, opts);
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = text;
  }
  return { ok: res.ok, status: res.status, data };
}

async function main() {
  console.log('Fetching trips...');
  const tripsRes = await request('GET', '/trips');
  if (!tripsRes.ok || !tripsRes.data?.length) {
    console.error('No trips. Start backend and ensure data is seeded.');
    process.exit(1);
  }
  const tripId = tripsRes.data[0].id;
  console.log('Using trip id:', tripId);

  console.log('Fetching seats...');
  const seatsRes = await request('GET', `/trips/${tripId}/seats`);
  if (!seatsRes.ok || !seatsRes.data?.length) {
    console.error('No seats for this trip.');
    process.exit(1);
  }
  const available = seatsRes.data.filter((s) => s.available);
  if (!available.length) {
    console.error('No available seats. Use a fresh DB or another trip.');
    process.exit(1);
  }
  const seatId = available[0].seatId;
  console.log('All 3 users will try to reserve seat id:', seatId, `(${available[0].seatNumber})\n`);

  const users = ['user-A', 'user-B', 'user-C'];
  console.log('Sending 3 concurrent reserve requests (same seat)...\n');

  const start = Date.now();
  const results = await Promise.all(
    users.map(async (userId) => {
      const r = await request('POST', '/reserve', { tripId, seatIds: [seatId] }, userId);
      return { userId, ...r };
    })
  );
  const elapsed = Date.now() - start;

  let successCount = 0;
  let conflictCount = 0;
  results.forEach(({ userId, ok, status, data }) => {
    const errMsg = data?.error || data?.message || (typeof data === 'string' ? data : '');
    if (ok) {
      successCount++;
      console.log(`  ${userId}: SUCCESS (${status}) – reserved`);
    } else {
      conflictCount++;
      console.log(`  ${userId}: FAILED (${status}) – ${errMsg || status}`);
    }
  });

  console.log('\n--- Result ---');
  console.log(`  Success: ${successCount}, Conflict/Fail: ${conflictCount}`);
  console.log(`  Time: ${elapsed}ms`);

  if (successCount === 1 && conflictCount === 2) {
    console.log('\n  Double-booking prevention works: only 1 user got the seat.');
    process.exit(0);
  } else {
    console.log('\n  Unexpected result: expected exactly 1 success and 2 conflicts.');
    process.exit(1);
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
