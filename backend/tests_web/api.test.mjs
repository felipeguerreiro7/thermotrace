import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createApi, audience} from '../app/portal/assets/api.mjs';

const reply = (data, status=200) => new Response(status===204?null:JSON.stringify(data), {status, headers:{'Content-Type':'application/json'}});
const tokens = n => ({access_token:`access-${n}`,refresh_token:`refresh-${n}`});
const me = {papel:'gestor',tipo_empresa:'embarcador',nome:'Pessoa sintética'};

test('download usa a mesma autenticação e devolve arquivo',async()=>{
  const api=base((url,o)=>{assert.equal(o.headers.Authorization,'Bearer access-1');return new Response('indice;temperatura\n0;5',{headers:{'Content-Type':'text/csv'}});});
  await api.login('sintetico@example.test','sintetico');
  const arquivo=await api.request('/sessoes/abc/leituras/def/temperaturas?formato=csv',{download:true});
  assert.equal(await arquivo.text(),'indice;temperatura\n0;5');
});
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

test('ação de escrita envia corpo e reaproveita a mesma chave após renovar o acesso',async()=>{
  const keys=[];
  const api=base(async(url,opts)=>{
    if(url.endsWith('/auth/refresh'))return reply(tokens(2));
    assert.equal(opts.method,'POST');assert.deepEqual(JSON.parse(opts.body),{motivo:'Encerrado no teste sintético.'});
    keys.push(opts.headers['Idempotency-Key']);
    return opts.headers.Authorization==='Bearer access-1'?reply({},401):reply({situacao:'revogado'});
  });
  await api.login('a@example.org','sintética');
  const r=await api.request('/compartilhamentos/abc/revogar',{method:'POST',body:{motivo:'Encerrado no teste sintético.'}});
  assert.equal(r.situacao,'revogado');assert.equal(keys.length,2);assert.equal(keys[0],keys[1]);assert.ok(keys[0].length>=16);
  const outra=base(()=>reply([]));await outra.login('b@example.org','sintética');
});

test('ativação envia código no corpo, sem token, e não abre sessão',async()=>{
  let pedido;
  const api=createApi(async(url,opts)=>{pedido={url,opts};return reply(null,204);});
  await api.ativar('a@example.org','ABCD-EFGH-JKMN','Senha-nova-sintetica-123');
  assert.equal(pedido.url,'/api/v1/auth/ativar');assert.equal(pedido.opts.method,'POST');
  assert.equal(pedido.opts.headers.Authorization,undefined);
  assert.deepEqual(JSON.parse(pedido.opts.body),{email:'a@example.org',codigo:'ABCD-EFGH-JKMN',nova_senha:'Senha-nova-sintetica-123'});
  await assert.rejects(api.request('/auth/me'),e=>e.status===401);
});

test('erro de validação diz o motivo da regra e o campo a revisar',async()=>{
  const api=base(()=>reply({mensagem:'Alguns campos não passaram na validação.',detalhes:{campos:[
    {campo:'corpo',problema:'Value error, Informe o código da carga ou pelo menos um documento.'},
    {campo:'documentos.0.chave_acesso',problema:"String should match pattern '^[A-Z0-9]{44}$'"}]}},422));
  await api.login('a@example.org','sintética');
  await assert.rejects(api.request('/remessas',{method:'POST',body:{}}),e=>e.status===422
    &&e.message.includes('Informe o código da carga')&&e.message.includes('"chave_acesso"')&&!e.message.includes('pattern')&&!e.message.includes('..'));
});

test('consulta não envia chave de idempotência',async()=>{
  const api=base((url,opts)=>{assert.equal(opts.headers['Idempotency-Key'],undefined);assert.equal(opts.method,'GET');return reply([]);});
  await api.login('a@example.org','sintética');await api.request('/compartilhamentos/recebidos');
});

test('falha no logout ainda remove a sessão local',async()=>{
  const api=base(()=>{throw new Error('offline');});
  await api.login('a@example.org','sintética');await assert.rejects(api.logout());
  await assert.rejects(api.request('/remessas'),e=>e.status===401);
});
