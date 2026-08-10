import { db } from '@/lib/firebase';
import { NextResponse } from 'next/server';
import { verifyAuth } from '@/lib/auth';

export async function GET(request: Request) {
  try {
    verifyAuth(request);
    const snapshot = await db.collection('history').get();
    const logs = snapshot.docs.map(doc => doc.data());
    let totalCatches = 0;
    const protectedCount = logs.filter((l) => (l as Record<string,unknown>).is_protected === true).length;
    const speciesMap: Record<string, number> = {};
    const locationMap: Record<string, number> = {};
    let freshTotal = 0, freshCount = 0;
    
    for (const log of logs) {
      const type = (log.type as number) || 0;
      
      // Parse Locations for all log types
      const loc = log.location as Record<string, unknown> | undefined;
      const pn = (loc?.name as string) || 'Unknown';
      if (pn !== 'Unknown') locationMap[pn] = (locationMap[pn] || 0) + 1;

      if (type === 0 || type === 3) {
        // Type 0 (Detection) and 3 (Protected)
        
        // Priority: Use structured metrics_json if provided
        if (log.metrics_json) {
          try {
            const metrics = typeof log.metrics_json === 'string' ? JSON.parse(log.metrics_json) : log.metrics_json;
            const species = metrics.species || {};
            for (const [name, count] of Object.entries(species)) {
              const c = count as number;
              speciesMap[name] = (speciesMap[name] || 0) + c;
              totalCatches += c;
            }
            if (metrics.eyeCondition && metrics.eyeCondition.fresh) {
              freshTotal += metrics.eyeCondition.fresh;
              freshCount += metrics.eyeCondition.total || 1;
            }
          } catch(e) {
            console.error("Failed to parse metrics_json", e);
          }
        } else {
          // Fallback: Fragile string parsing for older logs
          const title = (log.title as string) || '';
          const parts = title.split(',');
          for (const part of parts) {
            const trimmed = part.trim();
            if (!trimmed) continue;
            
            if (trimmed.includes(':')) {
              const entry = trimmed.split(':');
              if (entry.length === 2) {
                const name = entry[0].replace(/^(Protected: |Detection: )/, '').trim();
                if (name.toLowerCase() !== 'eyes') {
                  const count = parseInt(entry[1].trim()) || 0;
                  speciesMap[name] = (speciesMap[name] || 0) + count;
                  totalCatches += count;
                }
              }
            } else {
              let name = trimmed.replace(/^(Protected: |Detection: )/, '').trim();
              const lastSpace = name.lastIndexOf(' ');
              if (lastSpace !== -1 && name.substring(lastSpace + 1).includes('%')) {
                name = name.substring(0, lastSpace).trim();
              }
              if (!name.toLowerCase().startsWith('eyes')) {
                speciesMap[name] = (speciesMap[name] || 0) + 1;
                totalCatches += 1;
              }
            }
          }
        }
      } else if (type === 1) {
        // Type 1 (Freshness / Eye Condition)
        const details = (log.details as string) || '';
        const fm = details.match(/(?:Freshness|Eye Condition):\s*(\d+)%/i);
        if (fm) { 
          freshTotal += parseInt(fm[1]); 
          freshCount++; 
        }
      }
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
