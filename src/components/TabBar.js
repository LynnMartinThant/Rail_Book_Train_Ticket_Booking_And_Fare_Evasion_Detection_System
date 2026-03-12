import React from 'react';

const TABS = [
  { id: 'home', label: 'Home', icon: '🏠' },
  { id: 'journey', label: 'Journey', icon: '🚂' },
  { id: 'tickets', label: 'Tickets', icon: '🎫' },
  { id: 'profile', label: 'Profile', icon: '👤' },
];

function TabBar({ activeTab, onSelect }) {
  return (
    <nav
      className="fixed bottom-0 left-0 right-0 z-40 flex items-center justify-around border-t border-slate-200 bg-white safe-area-pb"
      role="tablist"
      aria-label="Primary navigation"
    >
      {TABS.map((tab) => (
        <button
          key={tab.id}
          type="button"
          role="tab"
          aria-selected={activeTab === tab.id}
          onClick={() => onSelect(tab.id)}
          className={`flex min-h-[56px] flex-1 flex-col items-center justify-center gap-0.5 px-2 py-2 text-sm transition ${
            activeTab === tab.id
              ? 'text-blue-600 font-semibold'
              : 'text-slate-500 hover:text-slate-800'
          }`}
        >
          <span className="text-xl" aria-hidden>{tab.icon}</span>
          <span>{tab.label}</span>
        </button>
      ))}
    </nav>
  );
}

export default TabBar;
export { TABS };
