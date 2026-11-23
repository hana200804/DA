import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');
  return {
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/icons/': {
          target: env.SIGNA_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
        '/api/v1': {
          target: env.SIGNA_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
        '/api/auth': {
          target: env.AUTH_TARGET || 'http://localhost:8000',
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/api\/auth/, ''),
        },
        '/api/signs': {
          target: env.SIGN_TARGET || 'http://localhost:12000',
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/api\/signs/, ''),
        },
        '/api/rtts': {
          target: env.RTTS_TARGET || 'http://localhost:11000',
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/api\/rtts/, ''),
        },
      },
    },
  };
});
