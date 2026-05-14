import jwt from 'jsonwebtoken';

export function verifyAuth(request: Request) {
  const authHeader = request.headers.get('Authorization') || request.headers.get('authorization');
  if (!authHeader?.startsWith('Bearer ')) {
    throw new Error('Missing or invalid token');
  }
  const token = authHeader.split(' ')[1];
  const secret = process.env.NEXTAUTH_SECRET;
  if (!secret) {
    throw new Error('Server configuration error');
  }
  return jwt.verify(token, secret);
}
