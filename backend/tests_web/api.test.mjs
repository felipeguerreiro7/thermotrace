import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createApi, audience} from '../app/portal/assets/api.mjs';

const reply = (data, status=200) => new Response(status===204?null:JSON.stringify(data), {status, headers:{'Content-Type':'application/json'}});
const tokens = n => ({access_token:`access-${n}`,refresh_token:`refresh-${n}`});
const me = {papel:'gestor',tipo_empresa:'embarcador',nome:'Pessoa sintética'};
function base(handler) { return createApi(async (url, options) => {
  if(url.endsWith('/auth/login')) return reply(tokens(1));
  if(url.endsWith('/auth/me')) return reply(me);
  return handler(url,options);
}); }

test('área segue tipo de empresa; gestor não significa transportadora',()=>{
  assert.equal(audience(me),'cliente');
  assert.equal(audience({...me,tipo_empresa:'transportadora',papel:'operador'}),'contratante');
  assert.equal(audience({...me,tipo_empresa:'ambos'}),'ambos');
  assert.equal(audience({papel:'admin',tipo_empresa:'plataforma'}),'staff');
  assert.equal(audience({...me,papel:'admin'}),null);
  assert.equal(audience({...me,tipo_empresa:'plataforma'}),null);
});

test('consulta usa mesma origem, sem cookies ou redirecionamento',async()=>{
  const api=base((url,opts)=>{
    assert.equal(url,'/api/v1/remessas');
    assert.equal(opts.credentials,'omit');assert.equal(opts.redirect,'error');assert.equal(opts.cache,'no-store');
    assert.equal(opts.headers.Authorization,'Bearer access-1');return reply([]);
  });
  await api.login('teste@example.org','senha sintética');await api.request('/remessas');
  await assert.rejects(api.request('//externo.example/roubar'));
  await assert.rejects(api.request('/../externo'));
});

test('401 simultâneos fazem uma única rotação',async()=>{
  let count=0;
  const api=base(async(url,opts)=>{
    if(url.endsWith('/auth/refresh')){count++;await new Promise(r=>setTimeout(r,15));return reply(tokens(2));}
    if(opts.headers.Authorization==='Bearer access-1')return reply({},401);
    return reply(['dado autorizado']);
  });
  await api.login('a@example.org','sintética');
  const result=await Promise.all([api.request('/remessas'),api.request('/usuarios')]);
  assert.equal(count,1);assert.deepEqual(result,[['dado autorizado'],['dado autorizado']]);
});

test('rotação com resultado incerto não reapresenta o refresh token',async()=>{
  let count=0;
  const api=base((url)=>{if(url.endsWith('/auth/refresh')){count++;throw new Error('conexão caiu');}return reply({},401);});
  await api.login('a@example.org','sintética');
  await assert.rejects(api.request('/remessas'),/conectar/);
  await assert.rejects(api.request('/remessas'),e=>e.status===401);
  assert.equal(count,1);
});

test('resposta de consulta antiga não reaparece depois do logout',async()=>{
  let deliver;
  const api=base(url=>url.endsWith('/auth/logout')?reply(null,204):new Promise(r=>{deliver=r;}));
  await api.login('a@example.org','sintética');
  const pending=api.request('/remessas');const rejected=assert.rejects(pending,e=>e.status===401);
  await api.logout();deliver(reply(['dado da conta antiga']));await rejected;
  await assert.rejects(api.request('/remessas'),e=>e.status===401);
});

test('refresh atrasado não restaura a conta depois de outro login',async()=>{
  let deliver;
  const api=base(url=>url.endsWith('/auth/refresh')?new Promise(r=>{deliver=r;}):reply({},401));
  await api.login('a@example.org','sintética');
  const pending=api.request('/remessas');const rejected=assert.rejects(pending,e=>e.status===401);
  while(!deliver)await new Promise(r=>setTimeout(r,0));
  await api.login('b@example.org','sintética');deliver(reply(tokens(2)));await rejected;
});

test('403 não tenta renovar sessão; erro do servidor não expõe detalhes internos',async()=>{
  let count=0;
  const api=base(url=>{count++;return url.endsWith('/remessas')?reply({mensagem:'interno secreto'},500):reply({},403);});
  await api.login('a@example.org','sintética');
  await assert.rejects(api.request('/usuarios'),e=>e.status===403);
  await assert.rejects(api.request('/remessas'),e=>e.status===500&&!e.message.includes('secreto'));
  assert.equal(count,2);
});

test('falha no logout ainda remove a sessão local',async()=>{
  const api=base(()=>{throw new Error('offline');});
  await api.login('a@example.org','sintética');await assert.rejects(api.logout());
  await assert.rejects(api.request('/remessas'),e=>e.status===401);
});
