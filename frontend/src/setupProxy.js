const { createProxyMiddleware } = require('http-proxy-middleware');

module.exports = function(app) {
  app.use(
    '/api',
    createProxyMiddleware({
      target: 'http://localhost:8080',
      changeOrigin: true,
      pathRewrite: {
        '^/api': '',
      },
    })
  );

  // Backward-compatible paths in case old client code calls API without /api prefix.
  app.use(
    ['/auth', '/cache', '/user', '/configuration', '/pipeline'],
    createProxyMiddleware({
      target: 'http://localhost:8080',
      changeOrigin: true,
    })
  );
};
