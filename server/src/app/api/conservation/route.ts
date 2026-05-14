import { db } from '@/lib/firebase';
import { NextResponse } from 'next/server';
import { verifyAuth } from '@/lib/auth';

export async function GET(request: Request) {
  try {
    verifyAuth(request);
    const snapshot = await db.collection('history').where('is_protected', '==', true).get();
    const logs = snapshot.docs.map(doc => doc.data());
    const monthlyMap: Record<string, number> = {};
    const speciesMap: Record<string, number> = {};
    for (const log of logs) {
      const ts = log.timestamp as number;
      const month = new Date(ts).toISOString().slice(0, 7);
      monthlyMap[month] = (monthlyMap[month] || 0) + 1;
      const title = (log.title as string) || '';
      const sp = title.replace('Protected: ', '').trim();
      if (sp) speciesMap[sp] = (speciesMap[sp] || 0) + 1;
    }
    const monthlyTrend = Object.entries(monthlyMap).sort(([a],[b]) => a.localeCompare(b))
      .map(([month, count]) => ({ month, count }));
    const speciesBreakdown = Object.entries(speciesMap).sort(([,a],[,b]) => b - a)
      .map(([species, count]) => ({ species, count }));
    return NextResponse.json({ success: true, data: {
      totalEncounters: logs.length, monthlyTrend, speciesBreakdown,
      hotspots: logs.filter(l => {
        const loc = l.location as Record<string,unknown> | undefined;
        return loc?.lat && loc?.lng;
      }).slice(0, 50).map(l => {
        const loc = l.location as Record<string,unknown>;
        return { lat: loc.lat, lng: loc.lng, species: ((l.title as string)||'').replace('Protected: ',''), time: l.timestamp };
      })
    }});
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : 'Unknown error';
    return NextResponse.json({ success: false, error: msg }, { status: 500 });
  }
}
