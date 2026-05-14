import { db } from '@/lib/firebase';
import { NextResponse } from 'next/server';
import { verifyAuth } from '@/lib/auth';

export async function GET(request: Request) {
  try {
    verifyAuth(request);
    const snapshot = await db.collection('history').get();
    const logs = snapshot.docs.map(doc => doc.data());
    const totalCatches = logs.length;
    const protectedCount = logs.filter((l) => (l as Record<string,unknown>).is_protected === true).length;
    const speciesMap: Record<string, number> = {};
    const locationMap: Record<string, number> = {};
    let freshTotal = 0, freshCount = 0;
    for (const log of logs) {
      const title = (log.title as string) || '';
      const sp = title.replace(/^(Protected: |Detection: )/, '').split(',')[0].trim();
      if (sp) speciesMap[sp] = (speciesMap[sp] || 0) + 1;
      const loc = log.location as Record<string, unknown> | undefined;
      const pn = (loc?.name as string) || 'Unknown';
      if (pn !== 'Unknown') locationMap[pn] = (locationMap[pn] || 0) + 1;
      const details = (log.details as string) || '';
      const fm = details.match(/Freshness:\s*(\d+)%/);
      if (fm) { freshTotal += parseInt(fm[1]); freshCount++; }
    }
    const topSpecies = Object.entries(speciesMap).sort(([,a],[,b]) => b - a).slice(0, 10)
      .map(([name, count]) => ({ name, count, pct: ((count / totalCatches) * 100).toFixed(1) }));
    const topLocations = Object.entries(locationMap).sort(([,a],[,b]) => b - a).slice(0, 10)
      .map(([name, count]) => ({ name, count }));
    const now = Date.now();
    const dailyMap: Record<string, number> = {};
    // Pre-fill all 30 days with 0 so the chart is always continuous
    for (let i = 29; i >= 0; i--) {
      const d = new Date(now - i * 86400000).toISOString().split('T')[0];
      dailyMap[d] = 0;
    }
    for (const log of logs) {
      const ts = log.timestamp as number;
      if (ts >= now - 30 * 86400000) {
        const d = new Date(ts).toISOString().split('T')[0];
        dailyMap[d] = (dailyMap[d] || 0) + 1;
      }
    }
    const dailyTrend = Object.entries(dailyMap).sort(([a],[b]) => a.localeCompare(b))
      .map(([date, count]) => ({ date: date.slice(5), count }));
    return NextResponse.json({ success: true, data: {
      totalCatches, protectedCount, uniqueSpecies: Object.keys(speciesMap).length,
      avgFreshness: freshCount > 0 ? (freshTotal / freshCount).toFixed(1) : 'N/A',
      topSpecies, topLocations, dailyTrend
    }});
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : 'Unknown error';
    return NextResponse.json({ success: false, error: msg }, { status: 500 });
  }
}
