import { db } from '@/lib/firebase';
import { NextResponse } from 'next/server';
import { verifyAuth } from '@/lib/auth';

export async function GET(request: Request) {
  try {
    verifyAuth(request);
    const snapshot = await db.collection('history')
      .where('is_protected', '==', true)
      .get();
    let logs = snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));
    
    // Sort in memory to avoid Firestore composite index requirement
    logs.sort((a, b) => {
      const tsA = (a as Record<string, unknown>).timestamp as number || 0;
      const tsB = (b as Record<string, unknown>).timestamp as number || 0;
      return tsB - tsA;
    });
    logs = logs.slice(0, 200);

    const speciesMap: Record<string, { count: number; lastSeen: number; locations: string[] }> = {};
    for (const log of logs) {
      const d = log as Record<string, unknown>;
      const sp = ((d.title as string) || '').replace('Protected: ', '').trim();
      const ts = d.timestamp as number;
      const loc = d.location as Record<string, unknown> | undefined;
      const pn = (loc?.name as string) || 'Unknown';
      if (!speciesMap[sp]) speciesMap[sp] = { count: 0, lastSeen: 0, locations: [] };
      speciesMap[sp].count++;
      if (ts > speciesMap[sp].lastSeen) speciesMap[sp].lastSeen = ts;
      if (!speciesMap[sp].locations.includes(pn)) speciesMap[sp].locations.push(pn);
    }
    const summary = Object.entries(speciesMap).sort(([,a],[,b]) => b.count - a.count)
      .map(([species, info]) => ({ species, count: info.count, lastSeen: new Date(info.lastSeen).toISOString(), locations: info.locations.slice(0,5) }));
    return NextResponse.json({ success: true, data: { totalProtectedDetections: logs.length, speciesSummary: summary, recentAlerts: logs.slice(0, 20) }});
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : 'Unknown error';
    return NextResponse.json({ success: false, error: msg }, { status: 500 });
  }
}
