'use client';
import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { LayoutDashboard, Fish, Shield, Users, ChevronDown, ChevronUp, MapPin, Clock, User, AlertTriangle, Tag } from 'lucide-react';
import ThemeToggle from '../components/ThemeToggle';
import { useTheme } from '../components/ThemeProvider';

interface Stats {
  totalCatches: number;
  protectedCount: number;
  uniqueSpecies: number;
  avgFreshness: string;
  topSpecies: { name: string; count: number; pct: string }[];
  topLocations: { name: string; count: number }[];
  dailyTrend: { date: string; count: number }[];
}

interface LogEntry {
  id: string;
  title: string;
  details: string;
  timestamp: number;
  is_protected: boolean;
  location?: { lat: number; lng: number; name: string };
  image_urls?: string[];
  user?: { name: string; pfp_url: string };
}

export default function DashboardPage() {
  const [stats, setStats] = useState<Stats | null>(null);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [activeTab, setActiveTab] = useState<'overview' | 'logs' | 'protected' | 'users'>('overview');
  const [loading, setLoading] = useState(true);
  const router = useRouter();

  useEffect(() => {
    const token = localStorage.getItem('token');
    if (!token) { router.push('/login'); return; }
    fetchData();
  }, [router]);

  const fetchData = async () => {
    setLoading(true);
    try {
      const token = localStorage.getItem('token');
      const headers = { 'Authorization': `Bearer ${token}` };
      const [statsRes, logsRes] = await Promise.all([
        fetch('/api/stats', { headers }), fetch('/api/logs?limit=50', { headers })
      ]);
      const statsData = await statsRes.json();
      const logsData = await logsRes.json();
      if (statsData.success) setStats(statsData.data);
      if (logsData.success) setLogs(logsData.data);
    } catch (e) { console.error('Fetch failed', e); }
    setLoading(false);
  };

  const logout = () => { localStorage.clear(); router.push('/login'); };

  if (loading) return (
    <div className="min-h-screen theme-bg-page flex items-center justify-center">
      <div className="animate-spin w-8 h-8 border-2 border-t-transparent rounded-full" style={{ borderColor: 'var(--spinner-color)', borderTopColor: 'transparent' }} />
    </div>
  );

  const tabs = [
    { id: 'overview' as const, label: 'Overview', icon: <LayoutDashboard className="w-4 h-4" /> },
    { id: 'logs' as const, label: 'Catch Logs', icon: <Fish className="w-4 h-4" /> },
    { id: 'protected' as const, label: 'Conservation', icon: <Shield className="w-4 h-4" /> },
    { id: 'users' as const, label: 'Users', icon: <Users className="w-4 h-4" /> },
  ];

  return (
    <div className="min-h-screen theme-bg-page">
      {/* Header */}
      <header className="sticky top-0 z-50 theme-bg-header backdrop-blur-xl" style={{ borderBottom: '1px solid var(--surface-border)' }}>
        <div className="max-w-7xl mx-auto px-4 py-3 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <img src="/logo.png" className="w-12 h-12 object-contain drop-shadow" alt="AquaVision Logo" />
            <div>
              <h1 className="theme-text-primary font-bold text-lg">AquaVision Monitor</h1>
              <p className="theme-text-muted text-xs">Real-time Fisheries Intelligence</p>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <ThemeToggle />
            <button onClick={fetchData} className="px-3 py-1.5 theme-btn-secondary rounded-lg text-sm transition">↻ Refresh</button>
            <button onClick={logout} className="px-3 py-1.5 bg-red-500/10 border border-red-500/20 rounded-lg text-red-400 text-sm hover:bg-red-500/20 transition">Logout</button>
          </div>
        </div>
      </header>

      {/* Tabs */}
      <div className="max-w-7xl mx-auto px-4 mt-4">
        <div className="flex gap-1 rounded-xl p-1 w-fit" style={{ background: 'var(--surface)' }}>
          {tabs.map(tab => (
            <button key={tab.id} onClick={() => setActiveTab(tab.id)}
              className={`flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition ${activeTab === tab.id ? 'theme-tab-active' : 'theme-text-muted hover:text-[var(--text-primary)]'}`}>
              {tab.icon} {tab.label}
            </button>
          ))}
        </div>
      </div>

      <div className="max-w-7xl mx-auto px-4 py-6">
        {activeTab === 'overview' && <OverviewTab stats={stats} />}
        {activeTab === 'logs' && <LogsTab logs={logs} />}
        {activeTab === 'protected' && <ProtectedTab />}
        {activeTab === 'users' && <UsersTab />}
      </div>
    </div>
  );
}

function StatCard({ label, value, sub, color }: { label: string; value: string | number; sub?: string; color: string }) {
  return (
    <div className="theme-bg-card rounded-2xl p-5">
      <p className="theme-text-muted text-xs font-medium uppercase tracking-wider">{label}</p>
      <p className={`text-3xl font-bold mt-1 ${color}`}>{value}</p>
      {sub && <p className="theme-text-muted text-xs mt-1">{sub}</p>}
    </div>
  );
}

function OverviewTab({ stats }: { stats: Stats | null }) {
  if (!stats) return <p className="theme-text-secondary">No data available</p>;
  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard label="Total Catches" value={stats.totalCatches} color="theme-text-primary" sub="All synced records" />
        <StatCard label="Unique Species" value={stats.uniqueSpecies} color="text-cyan-500" sub="Detected species" />
        <StatCard label="Protected Alerts" value={stats.protectedCount} color="text-red-400" sub="Conservation flags" />
        <StatCard label="Avg Freshness" value={`${stats.avgFreshness}%`} color="text-green-500" sub="Quality index" />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* Top Species */}
        <div className="theme-bg-card rounded-2xl p-5">
          <h3 className="theme-text-primary font-semibold mb-4">Top Species</h3>
          <div className="space-y-3">
            {stats.topSpecies.map((sp, i) => (
              <div key={i} className="flex items-center gap-3">
                <span className="theme-text-muted text-xs font-mono w-5">{i + 1}</span>
                <div className="flex-1">
                  <div className="flex justify-between items-center mb-1">
                    <span className="theme-text-primary text-sm">{sp.name}</span>
                    <span className="theme-text-secondary text-xs">{sp.count} ({sp.pct}%)</span>
                  </div>
                  <div className="h-1.5 theme-bar-track rounded-full overflow-hidden">
                    <div className="h-full theme-bar-fill rounded-full" style={{ width: `${sp.pct}%` }} />
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Top Locations */}
        <div className="theme-bg-card rounded-2xl p-5">
          <h3 className="theme-text-primary font-semibold mb-4">Top Locations</h3>
          <div className="space-y-3">
            {stats.topLocations.map((loc, i) => (
              <div key={i} className="flex items-center justify-between py-2" style={{ borderBottom: '1px solid var(--surface-divider)' }}>
                <div className="flex items-center gap-2">
                  <span className="text-cyan-500 text-sm">📍</span>
                  <span className="theme-text-primary text-sm">{loc.name}</span>
                </div>
                <span className="theme-text-secondary text-xs px-2 py-0.5 rounded-full" style={{ background: 'var(--surface)' }}>{loc.count}</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Daily Trend */}
      <div className="theme-bg-card rounded-2xl p-5">
        <h3 className="theme-text-primary font-semibold mb-4">Daily Catch Trend (30 days)</h3>
        {stats.dailyTrend.length === 0 ? (
          <div className="h-32 flex items-center justify-center">
            <p className="theme-text-muted text-sm">No catch data in the last 30 days</p>
          </div>
        ) : (
          <>
            <div className="relative" style={{ height: '160px' }}>
              {/* Y-axis max label */}
              {(() => {
                const max = Math.max(...stats.dailyTrend.map(x => x.count), 1);
                return (
                  <span className="absolute -top-1 left-0 theme-text-muted text-[10px] font-mono">{max}</span>
                );
              })()}
              {/* Horizontal grid lines */}
              <div className="absolute inset-0 flex flex-col justify-between pointer-events-none" style={{ opacity: 0.15 }}>
                {[0, 1, 2, 3].map(i => (
                  <div key={i} className="w-full border-b" style={{ borderColor: 'var(--text-muted)' }} />
                ))}
              </div>
              {/* Bars */}
              <div className="flex items-end gap-[2px] h-full relative z-10">
                {stats.dailyTrend.map((d, i) => {
                  const max = Math.max(...stats.dailyTrend.map(x => x.count), 1);
                  const hPx = d.count > 0 ? Math.max((d.count / max) * 152, 4) : 0;
                  return (
                    <div key={i} className="flex-1 flex flex-col items-center justify-end h-full group relative">
                      <div
                        className="opacity-0 group-hover:opacity-100 absolute -top-8 theme-tooltip text-[10px] px-2 py-1 rounded whitespace-nowrap transition-opacity z-20 pointer-events-none"
                        style={{ background: 'var(--tooltip-bg)', color: 'var(--text-primary)' }}
                      >
                        {d.date}: {d.count}
                      </div>
                      <div
                        className="w-full theme-bar-fill rounded-t transition-all duration-200 group-hover:opacity-100"
                        style={{ height: `${hPx}px`, opacity: d.count > 0 ? 0.75 : 0.15, minHeight: d.count > 0 ? '4px' : '1px', background: d.count > 0 ? undefined : 'var(--text-muted)' }}
                      />
                    </div>
                  );
                })}
              </div>
            </div>
            {/* X-axis labels */}
            <div className="flex mt-2">
              {stats.dailyTrend.map((d, i) => (
                <div key={i} className="flex-1 text-center">
                  {i % 5 === 0 ? (
                    <span className="theme-text-muted text-[9px] font-mono">{d.date}</span>
                  ) : null}
                </div>
              ))}
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function LogsTab({ logs }: { logs: LogEntry[] }) {
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const toggleExpand = (id: string) => {
    setExpandedId(prev => prev === id ? null : id);
  };

  const formatDateTime = (timestamp: number) => {
    const date = new Date(timestamp);
    const dateStr = date.toLocaleDateString('en-IN', {
      weekday: 'short', year: 'numeric', month: 'short', day: 'numeric'
    });
    const timeStr = date.toLocaleTimeString('en-IN', {
      hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true
    });
    return { dateStr, timeStr };
  };

  const parseDetails = (details: string) => {
    // Split on common separators to break into readable lines
    const parts = details
      .split(/[;,]/)
      .map(s => s.trim())
      .filter(s => s.length > 0);
    return parts;
  };

  return (
    <div className="theme-bg-card rounded-2xl overflow-hidden">
      <div className="p-5" style={{ borderBottom: '1px solid var(--surface-divider)' }}>
        <h3 className="theme-text-primary font-semibold text-lg">Recent Catch Logs</h3>
        <p className="theme-text-muted text-xs mt-1">Synced from AquaVision mobile app · Click on a log to view details</p>
      </div>
      <div className="theme-divide max-h-[700px] overflow-y-auto hide-scrollbar">
        {logs.map((log, i) => {
          const isExpanded = expandedId === (log.id || String(i));
          const { dateStr, timeStr } = formatDateTime(log.timestamp);
          const detailParts = parseDetails(log.details);

          return (
            <div key={log.id || i} className={`transition-all duration-300 ${log.is_protected ? 'border-l-2 border-red-500' : ''}`}>
              {/* Collapsed Header - Always visible */}
              <button
                onClick={() => toggleExpand(log.id || String(i))}
                className="w-full p-4 flex items-center gap-4 theme-bg-surface-hover transition text-left cursor-pointer"
              >
                {log.image_urls?.[0] && (
                  <img src={log.image_urls[0]} alt="" className="w-14 h-14 rounded-xl object-cover flex-shrink-0" style={{ border: '1px solid var(--surface-border)' }} />
                )}
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <h4 className="theme-text-primary text-sm font-semibold">{log.title}</h4>
                    {log.is_protected && (
                      <span className="flex items-center gap-1 px-2 py-0.5 bg-red-500/20 text-red-300 text-[10px] rounded-full font-bold uppercase tracking-wide">
                        <AlertTriangle className="w-3 h-3" /> Protected
                      </span>
                    )}
                  </div>
                  <p className="theme-text-muted text-xs mt-1">{dateStr} · {timeStr}</p>
                </div>
                <div className="flex-shrink-0 theme-text-muted">
                  {isExpanded ? <ChevronUp className="w-5 h-5" /> : <ChevronDown className="w-5 h-5" />}
                </div>
              </button>

              {/* Expanded Details */}
              {isExpanded && (
                <div className="px-4 pb-5 pt-0 animate-in fade-in duration-200">
                  <div className="ml-0 md:ml-[72px] space-y-4">
                    {/* Image enlarged */}
                    {log.image_urls?.[0] && (
                      <div className="rounded-xl overflow-hidden w-fit" style={{ border: '1px solid var(--surface-border)' }}>
                        <img src={log.image_urls[0]} alt="" className="max-w-[280px] max-h-[200px] object-cover" />
                      </div>
                    )}

                    {/* Details broken into readable lines */}
                    <div className="theme-bg-surface rounded-xl p-4 space-y-2">
                      <h5 className="theme-text-secondary text-xs font-semibold uppercase tracking-wider flex items-center gap-1.5 mb-3">
                        <Tag className="w-3.5 h-3.5" /> Details
                      </h5>
                      {detailParts.length > 1 ? (
                        <ul className="space-y-1.5">
                          {detailParts.map((part, idx) => (
                            <li key={idx} className="theme-text-secondary text-sm flex items-start gap-2">
                              <span className="text-cyan-500/60 mt-1.5 w-1.5 h-1.5 rounded-full bg-cyan-500/60 flex-shrink-0" />
                              {part}
                            </li>
                          ))}
                        </ul>
                      ) : (
                        <p className="theme-text-secondary text-sm leading-relaxed">{log.details}</p>
                      )}
                    </div>

                    {/* Metadata Grid */}
                    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
                      {/* Date & Time */}
                      <div className="theme-bg-surface rounded-xl p-3">
                        <div className="flex items-center gap-2 mb-1.5">
                          <Clock className="w-4 h-4 text-cyan-500" />
                          <span className="theme-text-muted text-xs font-medium uppercase tracking-wider">Date & Time</span>
                        </div>
                        <p className="theme-text-primary text-sm font-medium">{dateStr}</p>
                        <p className="theme-text-secondary text-sm">{timeStr}</p>
                      </div>

                      {/* Location */}
                      {(log.location?.name || log.location?.lat) && (
                        <div className="theme-bg-surface rounded-xl p-3">
                          <div className="flex items-center gap-2 mb-1.5">
                            <MapPin className="w-4 h-4 text-pink-400" />
                            <span className="theme-text-muted text-xs font-medium uppercase tracking-wider">Location</span>
                          </div>
                          {log.location?.name && (
                            <p className="theme-text-primary text-sm font-medium">{log.location.name}</p>
                          )}
                          {log.location?.lat && log.location?.lng && (
                            <p className="theme-text-muted text-xs mt-1 font-mono">
                              Lat: {log.location.lat.toFixed(6)}, Lng: {log.location.lng.toFixed(6)}
                            </p>
                          )}
                        </div>
                      )}

                      {/* User */}
                      {log.user?.name && (
                        <div className="theme-bg-surface rounded-xl p-3">
                          <div className="flex items-center gap-2 mb-1.5">
                            <User className="w-4 h-4 text-blue-400" />
                            <span className="theme-text-muted text-xs font-medium uppercase tracking-wider">Logged By</span>
                          </div>
                          <div className="flex items-center gap-2">
                            {log.user?.pfp_url && (
                              <img src={log.user.pfp_url} alt="" className="w-6 h-6 rounded-full object-cover" style={{ border: '1px solid var(--surface-border)' }} />
                            )}
                            <p className="theme-text-primary text-sm font-medium">{log.user.name}</p>
                          </div>
                        </div>
                      )}
                    </div>

                    {/* Protected Warning */}
                    {log.is_protected && (
                      <div className="bg-red-500/10 border border-red-500/20 rounded-xl p-4 flex items-start gap-3">
                        <AlertTriangle className="w-5 h-5 text-red-400 flex-shrink-0 mt-0.5" />
                        <div>
                          <p className="text-red-300 text-sm font-semibold">Protected Species Alert</p>
                          <p className="text-red-400/80 text-xs mt-1">This species is protected under the Wildlife Protection Act. Catching, trading, or possessing this species is illegal.</p>
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              )}
            </div>
          );
        })}
        {logs.length === 0 && <div className="p-8 text-center theme-text-muted">No logs yet. Sync data from the AquaVision app.</div>}
      </div>
    </div>
  );
}

function ProtectedTab() {
  const [data, setData] = useState<{ totalProtectedDetections: number; speciesSummary: { species: string; count: number; lastSeen: string; locations: string[] }[] } | null>(null);
  const [expandedSpecies, setExpandedSpecies] = useState<string | null>(null);
  useEffect(() => {
    const token = localStorage.getItem('token');
    fetch('/api/protected-species', { headers: { 'Authorization': `Bearer ${token}` } }).then(r => r.json()).then(d => { if (d.success) setData(d.data); });
  }, []);
  if (!data) return <div className="animate-spin w-6 h-6 border-2 border-t-transparent rounded-full mx-auto mt-12" style={{ borderColor: 'var(--spinner-color)', borderTopColor: 'transparent' }} />;

  const formatLastSeen = (dateStr: string) => {
    const date = new Date(dateStr);
    return {
      date: date.toLocaleDateString('en-IN', { weekday: 'short', year: 'numeric', month: 'short', day: 'numeric' }),
      time: date.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: true })
    };
  };

  return (
    <div className="space-y-4">
      <StatCard label="Total Protected Detections" value={data.totalProtectedDetections} color="text-red-400" sub="Wildlife Protection Act flagged species" />
      <div className="theme-bg-card rounded-2xl p-5">
        <h3 className="theme-text-primary font-semibold text-lg mb-2">Protected Species Encounters</h3>
        <p className="theme-text-muted text-xs mb-4">Click on a species to view details</p>
        <div className="space-y-3">
          {data.speciesSummary.map((sp, i) => {
            const isExpanded = expandedSpecies === sp.species;
            const { date, time } = formatLastSeen(sp.lastSeen);
            return (
              <div key={i} className="theme-protected-bg rounded-xl overflow-hidden transition-all">
                {/* Header - clickable */}
                <button
                  onClick={() => setExpandedSpecies(prev => prev === sp.species ? null : sp.species)}
                  className="w-full p-4 flex justify-between items-center hover:bg-red-500/10 transition cursor-pointer text-left"
                >
                  <div className="flex items-center gap-3">
                    <AlertTriangle className="w-5 h-5 text-red-400 flex-shrink-0" />
                    <div>
                      <h4 className="theme-text-primary font-semibold">{sp.species}</h4>
                      <p className="theme-text-muted text-xs mt-0.5">Last seen: {date}</p>
                    </div>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className="px-3 py-1 bg-red-500/20 text-red-300 text-sm font-bold rounded-full">{sp.count}</span>
                    {isExpanded ? <ChevronUp className="w-4 h-4 theme-text-muted" /> : <ChevronDown className="w-4 h-4 theme-text-muted" />}
                  </div>
                </button>

                {/* Expanded details */}
                {isExpanded && (
                  <div className="px-4 pb-4 space-y-3 pt-3" style={{ borderTop: '1px solid var(--protected-border-subtle)' }}>
                    {/* Date & Time */}
                    <div className="theme-bg-surface rounded-xl p-3">
                      <div className="flex items-center gap-2 mb-1.5">
                        <Clock className="w-4 h-4 text-cyan-500" />
                        <span className="theme-text-muted text-xs font-medium uppercase tracking-wider">Last Seen</span>
                      </div>
                      <p className="theme-text-primary text-sm font-medium">{date}</p>
                      <p className="theme-text-secondary text-sm">{time}</p>
                    </div>

                    {/* Locations listed individually */}
                    {sp.locations.length > 0 && (
                      <div className="theme-bg-surface rounded-xl p-3">
                        <div className="flex items-center gap-2 mb-2">
                          <MapPin className="w-4 h-4 text-pink-400" />
                          <span className="theme-text-muted text-xs font-medium uppercase tracking-wider">Detection Locations</span>
                        </div>
                        <ul className="space-y-1.5">
                          {sp.locations.map((loc, idx) => (
                            <li key={idx} className="theme-text-secondary text-sm flex items-center gap-2">
                              <span className="w-1.5 h-1.5 rounded-full bg-pink-400/60 flex-shrink-0" />
                              {loc}
                            </li>
                          ))}
                        </ul>
                      </div>
                    )}

                    {/* Alert */}
                    <div className="bg-red-500/10 border border-red-500/20 rounded-xl p-3 flex items-start gap-3">
                      <Shield className="w-4 h-4 text-red-400 flex-shrink-0 mt-0.5" />
                      <p className="text-red-400/80 text-xs">This species is protected under the Wildlife Protection Act. All encounters are automatically flagged and logged for conservation monitoring.</p>
                    </div>
                  </div>
                )}
              </div>
            );
          })}
          {data.speciesSummary.length === 0 && <p className="theme-text-muted text-center py-4">No protected species detected yet</p>}
        </div>
      </div>
    </div>
  );
}

function UsersTab() {
  const [data, setData] = useState<{ totalUsers: number; users: { id: string; name: string; totalLogs: number; lastActive: string; protectedCount: number }[] } | null>(null);
  useEffect(() => {
    const token = localStorage.getItem('token');
    fetch('/api/users', { headers: { 'Authorization': `Bearer ${token}` } }).then(r => r.json()).then(d => { if (d.success) setData(d.data); });
  }, []);
  if (!data) return <div className="animate-spin w-6 h-6 border-2 border-t-transparent rounded-full mx-auto mt-12" style={{ borderColor: 'var(--spinner-color)', borderTopColor: 'transparent' }} />;
  return (
    <div className="space-y-4">
      <StatCard label="Active Users" value={data.totalUsers} color="text-blue-400" sub="Fishermen using AquaVision" />
      <div className="theme-bg-card rounded-2xl overflow-hidden">
        <table className="w-full">
          <thead><tr style={{ borderBottom: '1px solid var(--surface-border)' }}>
            <th className="text-left theme-table-header text-xs p-3 font-medium">User</th>
            <th className="text-left theme-table-header text-xs p-3 font-medium">Total Logs</th>
            <th className="text-left theme-table-header text-xs p-3 font-medium">Protected</th>
            <th className="text-left theme-table-header text-xs p-3 font-medium">Last Active</th>
          </tr></thead>
          <tbody className="theme-divide">
            {data.users.map((u, i) => (
              <tr key={i} className="theme-table-row">
                <td className="p-3 theme-text-primary text-sm">{u.name}</td>
                <td className="p-3 theme-text-secondary text-sm">{u.totalLogs}</td>
                <td className="p-3"><span className={`${u.protectedCount > 0 ? 'text-red-400' : 'theme-text-muted'} text-sm`}>{u.protectedCount}</span></td>
                <td className="p-3 theme-text-muted text-xs">{new Date(u.lastActive).toLocaleDateString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
