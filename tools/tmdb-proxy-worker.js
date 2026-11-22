// TMDB 反代（Cloudflare Worker）
//
// 用途：国内直连 api.themoviedb.org 会 TCP 不通，用这个 Worker 做一跳中转，
//       把「接口地址」填成 https://<你的子域>.workers.dev 即可。
//
// 部署步骤：
//   1. 打开 https://dash.cloudflare.com → Workers & Pages → 新建 Worker
//   2. 把本文件内容整段粘进在线编辑器，保存并部署
//   3. 复制 Worker 域名（形如 https://tmdb-proxy.xxx.workers.dev）
//   4. 打开「转存喵」→ 设置 → TMDB → 接口地址 填该域名（末尾不要带 /）
//
// 安全说明：凭证由手机直接带给 TMDB，Worker 只中转，不记录、不落盘；
//           但仍然建议自建，不要用来路不明的公共镜像（对方能看到你的 Key）。

const UPSTREAM = 'https://api.themoviedb.org';

export default {
  async fetch(request) {
    const url = new URL(request.url);
    if (!url.pathname.startsWith('/3/')) {
      return new Response('tmdb proxy: only /3/* is allowed', { status: 404 });
    }

    const target = UPSTREAM + url.pathname + url.search;
    const headers = new Headers();
    // 只透传鉴权与协商头，其余丢弃
    for (const name of ['authorization', 'accept']) {
      const v = request.headers.get(name);
      if (v) headers.set(name, v);
    }

    const init = {
      method: request.method,
      headers,
      // TMDB 是只读 GET，无需 body
      body: request.method === 'GET' || request.method === 'HEAD' ? undefined : await request.text(),
    };

    const resp = await fetch(target, init);
    const outHeaders = new Headers(resp.headers);
    outHeaders.set('access-control-allow-origin', '*');
    outHeaders.delete('content-encoding'); // Worker 已解压，避免长度不一致
    return new Response(resp.body, {
      status: resp.status,
      headers: outHeaders,
    });
  },
};
