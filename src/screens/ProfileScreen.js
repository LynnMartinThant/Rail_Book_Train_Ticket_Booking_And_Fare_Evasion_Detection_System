import React from 'react';
import TicketsPanel from '../components/TicketsPanel';

function ProfileScreen({ user, onLogout, bookings, bookingsLoading, onBuyTicket }) {
  return (
    <div className="space-y-6 pb-24">
      <section className="rounded-xl border border-slate-200 bg-white p-4">
        <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wide mb-2">Account</h2>
        <p className="text-slate-900 font-medium">{user && user.email}</p>
        {user && user.userId && (
          <p className="text-slate-500 text-sm mt-1">
            User ID: <span className="font-mono text-slate-700">{user.userId}</span>
          </p>
        )}
      </section>

      <section className="rounded-xl border border-slate-200 bg-white p-4">
        <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wide mb-4">Tickets</h2>
        <TicketsPanel bookings={bookings} bookingsLoading={bookingsLoading} onBuyTicket={onBuyTicket} />
      </section>

      <section className="rounded-xl border border-slate-200 bg-white p-4">
        <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wide mb-2">Payment methods</h2>
        <p className="text-slate-600 text-sm">Visa, Apple Pay and Test (demo) available at checkout.</p>
      </section>
      <section>
        <button type="button" onClick={onLogout} className="w-full rounded-xl border-2 border-slate-300 py-3 text-base font-semibold text-slate-700 hover:bg-slate-50">
          Log out
        </button>
      </section>
    </div>
  );
}

export default ProfileScreen;
