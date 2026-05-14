import { NextResponse } from 'next/server';
import jwt from 'jsonwebtoken';

export async function POST(request: Request) {
  try {
    const SECRET = process.env.NEXTAUTH_SECRET;
    const ADMIN_EMAIL = process.env.ADMIN_EMAIL;
    const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD;

    if (!SECRET || !ADMIN_EMAIL || !ADMIN_PASSWORD) {
      return NextResponse.json({ success: false, error: 'Server configuration error' }, { status: 500 });
    }

    const { email, password } = await request.json();
    if (email === ADMIN_EMAIL && password === ADMIN_PASSWORD) {
      const token = jwt.sign({ email, role: 'admin' }, SECRET, { expiresIn: '7d' });
      return NextResponse.json({ success: true, token, user: { email, role: 'admin' } });
    }
    return NextResponse.json({ success: false, error: 'Invalid credentials' }, { status: 401 });
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : 'Unknown error';
    return NextResponse.json({ success: false, error: msg }, { status: 500 });
  }
}
