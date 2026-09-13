/** Sessão apenas em memória. Não persiste credenciais no navegador. */
export class ApiError extends Error {
  constructor(message, status = 0) { super(message); this.status = status; }
}

export function audience(user) {
  if (user.papel === 'admin' && user.tipo_empresa === 'plataforma') return 'staff';
  if (!['gestor', 'operador'].includes(user.papel)) return null;
  return {transportadora: 'contratante', embarcador: 'cliente', ambos: 'ambos'}[user.tipo_empresa] ?? null;
}

export function createApi(fetcher = globalThis.fetch.bind(globalThis)) {
  let access = '', refresh = '', generation = 0, pendingRefresh = null;
  function clear() { access = ''; refresh = ''; generation++; pendingRefresh = null; }
  const expired = () => new ApiError('Sua sessão terminou. Entre novamente.', 401);

  async function send(path, {method = 'GET', body, token = ''} = {}) {
    // Impede o envio de credenciais a destinos externos, inclusive redirects.
    if (!/^\/[a-z][a-z0-9/?=&_%.-]*$/i.test(path) || path.includes('..')) throw new ApiError('Endereço inválido.');
    let response;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 20000);
    try {
      response = await fetcher('/api/v1' + path, {
        method, credentials: 'omit', cache: 'no-store', redirect: 'error', signal: controller.signal,
        headers: {...(body ? {'Content-Type': 'application/json'} : {}), ...(token ? {Authorization: `Bearer ${token}`} : {})},
        ...(body ? {body: JSON.stringify(body)} : {}),
      });
    } catch { throw new ApiError('Não foi possível conectar. Verifique sua conexão e tente novamente.'); }
    finally { clearTimeout(timeout); }
    if (!response.ok) {
      const data = await response.json().catch(() => ({}));
      const fallback = {401:'E-mail ou senha inválidos, ou sessão encerrada.',403:'Sua conta não tem acesso a esta informação.',404:'Registro não encontrado para sua empresa.',422:'Confira os dados informados.',429:'Muitas tentativas. Aguarde um pouco para tentar novamente.'};
      throw new ApiError(response.status >= 500 ? 'O serviço está indisponível. Tente novamente em instantes.' : (data.mensagem || fallback[response.status] || 'Não foi possível concluir a consulta.'), response.status);
    }
    if (response.status === 204) return null;
    return response.json();
  }

  async function renew() {
    if (pendingRefresh) return pendingRefresh;
    if (!refresh) throw expired();
    const current = generation, previous = refresh;
    refresh = ''; // Uma renovação incerta nunca reutiliza o token rotativo.
    const task = (async () => {
      try {
        const data = await send('/auth/refresh', {method:'POST', body:{refresh_token:previous}});
        if (current !== generation) throw expired();
        access = data.access_token; refresh = data.refresh_token;
      } catch (error) { if (current === generation) clear(); throw error; }
    })();
    pendingRefresh = task;
    try { await task; } finally { if (pendingRefresh === task) pendingRefresh = null; }
  }

  async function request(path) {
    if (!access) throw expired();
    const current = generation, previous = access;
    try {
      const data = await send(path, {token:previous});
      if (current !== generation) throw expired();
      return data;
    } catch (error) {
      if (current !== generation) throw expired();
      if (error.status !== 401) throw error;
      if (access === previous) await renew();
      if (current !== generation) throw expired();
      try {
        const data = await send(path, {token:access});
        if (current !== generation) throw expired();
        return data;
      } catch (retryError) {
        if (current === generation && retryError.status === 401) clear();
        throw retryError;
      }
    }
  }

  return {
    request,
    async login(email, senha) {
      clear();
      const current = generation;
      try {
        const data = await send('/auth/login', {method:'POST', body:{email, senha}});
        if (current !== generation) throw expired();
        access = data.access_token; refresh = data.refresh_token;
        return await request('/auth/me');
      } catch (error) { if (current === generation) clear(); throw error; }
    },
    async logout() {
      // Renova primeiro quando necessário; o servidor revoga toda a família.
      try {
        if (access) {
          await request('/auth/me');
          if (access) await send('/auth/logout', {method:'POST', token:access});
        }
      } finally { clear(); }
    },
  };
}
