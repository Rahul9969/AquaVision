import { db } from '@/lib/firebase';
import { NextResponse } from 'next/server';
import { verifyAuth } from '@/lib/auth';

export async function GET(request: Request) {
  try {
    verifyAuth(request);
    const { searchParams } = new URL(request.url);
    const species = searchParams.get('species');
    const limit = parseInt(searchParams.get('limit') || '100');
    let query: FirebaseFirestore.Query = db.collection('history');
    if (species) {
      query = query.where('title', '>=', species).where('title', '<=', species + '\uf8ff');
    }
    query = query.orderBy('timestamp', 'desc').limit(limit);
    const snapshot = await query.get();
    const logs = snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));
    return NextResponse.json({ success: true, count: logs.length, data: logs });
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : 'Unknown error';
    return NextResponse.json({ success: false, error: msg }, { status: 500 });
  }
}
