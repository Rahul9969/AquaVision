import { db } from '@/lib/firebase';
import { NextResponse } from 'next/server';
import { verifyAuth } from '@/lib/auth';

export async function GET(request: Request) {
  try {
    verifyAuth(request);
    const snapshot = await db.collection('history').get();
    const logs = snapshot.docs.map(doc => doc.data());
    const userMap: Record<string, { name: string; pfpUrl: string; totalLogs: number; lastActive: number; protectedCount: number }> = {};
    for (const log of logs) {
      const user = log.user as Record<string, unknown> | undefined;
      const uid = (user?.id as string) || 'unknown';
      const uname = (user?.name as string) || 'Anonymous';
      const upfp = (user?.pfp_url as string) || '';
      const ts = log.timestamp as number;
      const isProt = log.is_protected as boolean;
      if (!userMap[uid]) userMap[uid] = { name: uname, pfpUrl: upfp, totalLogs: 0, lastActive: 0, protectedCount: 0 };
      userMap[uid].totalLogs++;
      if (ts > userMap[uid].lastActive) userMap[uid].lastActive = ts;
      if (isProt) userMap[uid].protectedCount++;
    }
    const users = Object.entries(userMap).sort(([,a],[,b]) => b.totalLogs - a.totalLogs)
      .map(([id, info]) => ({ id, ...info, lastActive: new Date(info.lastActive).toISOString() }));
    return NextResponse.json({ success: true, data: { totalUsers: users.length, users }});
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : 'Unknown error';
    return NextResponse.json({ success: false, error: msg }, { status: 500 });
  }
}
