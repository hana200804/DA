import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// Serves the included demo bundle without npm installation. API mode needs a reverse proxy.
const root = fileURLToPath(new URL('../dist/', import.meta.url));
const mime = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript',
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
};
const server = http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://127.0.0.1');
    const pathname = decodeURIComponent(url.pathname);
    let file = path.resolve(root, '.' + pathname);
    if (
      file !== path.resolve(root) &&
      !file.startsWith(path.resolve(root) + path.sep)
    ) {
      response.writeHead(403).end();
      return;
    }
    if (pathname === '/') file = path.join(root, 'index.html');
    const body = await readFile(file);
    response.writeHead(200, {
      'Content-Type': mime[path.extname(file)] || 'application/octet-stream',
      'Cache-Control': 'no-store',
    });
    response.end(body);
  } catch {
    response
      .writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' })
      .end(
        'Không tìm thấy tài nguyên. Kiểm tra thư mục dist hoặc chạy npm run build.',
      );
  }
});
server.on('error', (error) => {
  console.error(
    error.code === 'EADDRINUSE'
      ? 'Cổng 5173 đang được sử dụng. Dừng ứng dụng đang dùng cổng này rồi thử lại.'
      : error.message,
  );
  process.exitCode = 1;
});
server.listen(5173, '127.0.0.1', () =>
  console.log('VisionMap demo: http://127.0.0.1:5173\nNhấn Ctrl+C để dừng.'),
);
