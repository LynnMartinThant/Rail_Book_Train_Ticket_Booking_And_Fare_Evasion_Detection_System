import React from 'react';

function Landing({ onGoToLogin, onGoToRegister }) {
  return (
    <div className="landing min-h-screen flex flex-col">
      <header className="border-b border-slate-700/50 bg-slate-900/80 backdrop-blur">
        <div className="mx-auto max-w-5xl px-4 py-4 flex items-center justify-between">
          <span className="text-lg font-semibold tracking-tight text-white" style={{ fontFamily: "'Syne', sans-serif" }}>
            RailBook
          </span>
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={onGoToLogin}
              className="text-sm text-slate-300 hover:text-white transition"
            >
              Log in
            </button>
            <button
              type="button"
              onClick={onGoToRegister}
              className="rounded-lg bg-amber-500 px-4 py-2 text-sm font-medium text-slate-900 hover:bg-amber-400 transition"
            >
              Sign up
            </button>
          </div>
        </div>
      </header>

      <main className="flex-1 flex flex-col items-center justify-center px-4 py-16 text-center">
        <h1
          className="text-4xl sm:text-5xl md:text-6xl font-bold text-white tracking-tight max-w-3xl mb-4"
          style={{ fontFamily: "'Syne', sans-serif" }}
        >
          Book trains. Pay in a tap. Travel with confidence.
        </h1>
        <p className="text-slate-400 text-lg sm:text-xl max-w-xl mb-10">
          Reserve seats, pay with Visa or Apple Pay, and get instant tickets. Smart geofencing keeps you informed at the station.
        </p>
        <div className="flex flex-col sm:flex-row gap-4">
          <button
            type="button"
            onClick={onGoToRegister}
            className="rounded-xl bg-amber-500 px-8 py-4 text-base font-semibold text-slate-900 hover:bg-amber-400 transition shadow-lg shadow-amber-500/25"
          >
            Get started
          </button>
          <button
            type="button"
            onClick={onGoToLogin}
            className="rounded-xl border border-slate-500 bg-slate-800/50 px-8 py-4 text-base font-medium text-white hover:bg-slate-700/50 transition"
          >
            Log in
          </button>
        </div>
      </main>

      <section className="border-t border-slate-700/50 bg-slate-900/40 py-16 px-4">
        <div className="mx-auto max-w-4xl">
          <h2 className="text-xl font-semibold text-white mb-8 text-center" style={{ fontFamily: "'Syne', sans-serif" }}>
            Why RailBook
          </h2>
          <div className="grid sm:grid-cols-3 gap-8">
            <div className="rounded-xl border border-slate-700/50 bg-slate-800/30 p-6 text-left">
              <div className="text-2xl mb-3">🎫</div>
              <h3 className="font-semibold text-white mb-2">Instant booking</h3>
              <p className="text-sm text-slate-400">
                Choose your trip and seats, then pay with card or Apple Pay. Your ticket is confirmed as soon as payment clears.
              </p>
            </div>
            <div className="rounded-xl border border-slate-700/50 bg-slate-800/30 p-6 text-left">
              <div className="text-2xl mb-3">📍</div>
              <h3 className="font-semibold text-white mb-2">Station-aware</h3>
              <p className="text-sm text-slate-400">
                When you enter a station we can remind you to buy a ticket or scan an existing one—no more last-minute rushes.
              </p>
            </div>
            <div className="rounded-xl border border-slate-700/50 bg-slate-800/30 p-6 text-left">
              <div className="text-2xl mb-3">📱</div>
              <h3 className="font-semibold text-white mb-2">One place for everything</h3>
              <p className="text-sm text-slate-400">
                View and manage your bookings, get notifications, and request refunds—all in the app.
              </p>
            </div>
          </div>
        </div>
      </section>

      <footer className="border-t border-slate-700/50 py-6 px-4">
        <div className="mx-auto max-w-4xl flex flex-col sm:flex-row items-center justify-between gap-4 text-sm text-slate-500">
          <span>RailBook – train ticket booking with geofencing and secure payment.</span>
          <a
            href="/admin"
            className="text-slate-400 hover:text-white transition"
          >
            Operator admin →
          </a>
        </div>
      </footer>

      <style>{`
        .landing {
          background: linear-gradient(160deg, #0f172a 0%, #1e293b 40%, #0f172a 100%);
        }
      `}</style>
    </div>
  );
}

export default Landing;
