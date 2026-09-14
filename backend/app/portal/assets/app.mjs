import {createApi, audience} from './api.mjs';
import {temperaturePanel} from './chart.mjs';

const api = createApi();
const root = document.querySelector('#app');
const labels = {staff:'Equipe ThermoTrace', cliente:'Cliente · dono da carga', contratante:'Contratante · transportadora', ambos:'Cliente e contratante'};
const statuses = {preparacao:'Em preparação', aguardando_aceite:'Aguardando aceite', aguardando_coleta:'Aguardando coleta', em_transporte:'Em transporte', entregue_aguardando_leitura:'Aguardando leitura final', concluida:'Concluída', cancelada:'Cancelada'};
let user, area, content, page = 'cargas', view = 0;
const date = value => value ? new Intl.DateTimeFormat('pt-BR', {dateStyle:'short', timeStyle:'short'}).format(new Date(value)) : 'Não informado';
function el(tag, text, cls) { const n = document.createElement(tag); if (text !== undefined) n.textContent = text; if (cls) n.className = cls; return n; }
function button(text, action, cls = 'secondary') { const b = el('button', text, cls); b.type = 'button'; b.addEventListener('click', action); return b; }
function badge(text) { return el('span', text, 'badge'); }
function empty(title, message) { const n = el('section', undefined, 'empty'); n.append(el('h2', title), el('p', message)); return n; }
function heading(title, subtitle) { const n = el('header', undefined, 'heading'); const copy = el('div'); copy.append(el('h1', title), el('p', subtitle, 'muted')); n.append(copy); return n; }
function notice(text) { return el('p', text, 'notice'); }
function values(items) { const n = el('dl', undefined, 'key-values'); for (const [key, value] of items) n.append(el('dt', key), el('dd', value ?? 'Não informado')); return n; }
function table(headers, rows) {
  const wrap = el('div', undefined, 'table-wrap'), t = el('table'), head = el('thead'), tr = el('tr'), body = el('tbody');
  headers.forEach(x => { const h = el('th', x); h.scope = 'col'; tr.append(h); }); head.append(tr);
  rows.forEach(row => { const r = el('tr'); row.forEach(value => { const cell = el('td'); cell.append(value instanceof Node ? value : document.createTextNode(String(value ?? '—'))); r.append(cell); }); body.append(r); });
  t.append(head, body); wrap.append(t); return wrap;
}
function json(value) { return el('pre', JSON.stringify(value, null, 2), 'json'); }
function field(label, type, name, autocomplete) { const l = el('label', label, 'field'), i = el('input'); i.type=type; i.name=name; i.required=true; i.autocomplete=autocomplete; l.append(i); return [l,i]; }
function showError(error, target) {
  if (error.status === 401 && user) { login('Sua sessão terminou. Entre novamente.'); return; }
  const msg = el('p', error.message || 'Não foi possível carregar.', 'error'); msg.setAttribute('role','alert'); target.append(msg);
}

function login(message = '') {
  view++; user = null;
  root.innerHTML = `<main class="login-layout" id="content"><section class="login-story"><div><span class="brand">Thermo<span>Trace</span></span><div class="tagline">COLD CHAIN INTELLIGENCE</div></div><div><h1>Cada carga.<br>Cada coleta.<br>Um histórico.</h1><p>Acompanhe suas remessas e consulte as evidências de transporte em um só lugar.</p><div class="journey"><div><strong>01</strong>Identificar a carga</div><div><strong>02</strong>Acompanhar coletas</div><div><strong>03</strong>Conferir evidências</div></div></div><p class="hint">Portal de homologação · acesso restrito</p></section><section class="login-panel"><div class="login-card"><p class="eyebrow">Acesso ao portal</p><h2>Entre na sua conta</h2><p class="muted">Use o acesso da sua empresa. Sua área será aberta conforme o cadastro.</p><form id="login-form"></form><p class="hint">Ainda não tem acesso? Solicite o cadastro ao responsável pela ThermoTrace.</p><p class="hint">Ao atualizar ou fechar esta página, será necessário entrar novamente.</p></div></section></main>`;
  const form = document.querySelector('#login-form'), [emailLabel,email] = field('E-mail','email','email','username'), [passwordLabel,password] = field('Senha','password','senha','current-password');
  email.maxLength = 254; password.maxLength = 256;
  const error = el('p', message, 'error'); error.setAttribute('role','alert');
  const submit = el('button','Entrar','primary wide'); submit.type='submit';
  form.append(emailLabel,passwordLabel,error,submit);
  form.addEventListener('submit', async e => {
    e.preventDefault(); error.textContent=''; submit.disabled=true; submit.textContent='Entrando…';
    try {
      const signed = await api.login(email.value.trim(), password.value);
      password.value='';
      const nextArea = audience(signed);
      if (!nextArea) { await api.logout(); throw new Error('Este cadastro ainda não tem uma área disponível. Fale com o responsável pela ThermoTrace.'); }
      user=signed; area=nextArea; shell();
    } catch (err) { password.value=''; error.textContent=err.message; }
    finally { submit.disabled=false; submit.textContent='Entrar'; }
  });
}

function shell() {
  root.innerHTML = `<div class="shell"><aside class="sidebar"><div><span class="brand">Thermo<span>Trace</span></span><div class="tagline">COLD CHAIN INTELLIGENCE</div></div><nav aria-label="Navegação principal"></nav><div class="sidebar-bottom">Homologação<br>Histórico com origem e autoria.<br>Acesso limitado à sua permissão.</div></aside><div class="main"><header class="topbar"><span id="area" class="eyebrow"></span><div class="top-actions"><div class="identity" id="identity"></div><div id="exit"></div></div></header><main id="content" class="content" tabindex="-1" aria-live="polite"></main></div></div>`;
  document.querySelector('#area').textContent=labels[area];
  document.querySelector('#identity').append(el('strong',user.nome),el('span',user.empresa));
  document.querySelector('#exit').append(button('Sair', async e => {
    e.currentTarget.disabled=true; view++;
    // Remove imediatamente qualquer dado exibido durante a revogação.
    content.replaceChildren(el('p','Encerrando sua sessão…','loading'));
    let message=''; try { await api.logout(); } catch { message='Você saiu deste navegador. Não foi possível confirmar a revogação no servidor.'; }
    login(message);
  }));
  const nav=document.querySelector('nav');
  const items=area==='staff' ? [['empresas','Empresas cadastradas']] : [['cargas','Minhas cargas'], ...(user.papel==='gestor' ? [['equipe','Equipe da empresa'],['auditoria','Auditoria']] : [])];
  items.forEach(([key,label]) => { const b=button(label,()=>navigate(key),'nav-button'); b.dataset.page=key; nav.append(b); });
  content=document.querySelector('#content'); navigate(items[0][0]);
}

async function navigate(key, offset=0, query='') {
  page=key; const ticket=++view;
  document.querySelectorAll('[data-page]').forEach(n => n.setAttribute('aria-current',n.dataset.page===key?'page':'false'));
  const titles={cargas:['Minhas cargas',area==='contratante'?'Consulte as cargas cadastradas na sua transportadora.':'Localize a carga e acompanhe os registros recebidos.'],empresas:['Empresas cadastradas','Visão administrativa da equipe ThermoTrace.'],equipe:['Equipe da empresa','Pessoas com acesso ao ambiente da sua empresa.'],auditoria:['Auditoria','Eventos registrados pelo servidor, do mais antigo ao mais recente.']};
  content.replaceChildren(heading(...titles[key]));
  if (key==='cargas') {
    content.append(notice('Nesta versão, cada empresa consulta suas próprias cargas. O compartilhamento entre transportadora e dono da carga está em preparação.'));
    const form=el('form',undefined,'search'), l=el('label','Código da carga ou documento fiscal','field'), input=el('input');
    input.name='busca'; input.value=query; input.maxLength=96; input.placeholder='Ex.: REM-2026-00184 ou chave da NF-e'; l.append(input);
    const search=el('button','Buscar','primary'); search.type='submit';
    form.append(l,search,button('Limpar',()=>navigate(key))); form.addEventListener('submit',e=>{e.preventDefault(); navigate(key,0,input.value.trim());}); content.append(form);
  }
  if (key==='empresas') content.append(notice('O acesso de suporte ao conteúdo das cargas exigirá autorização e registro de auditoria. Esta área mostra os cadastros das empresas.'));
  const results=el('section'); results.setAttribute('aria-label','Resultados'); results.append(el('p','Carregando…','loading')); content.append(results);
  try {
    const path= key==='empresas'?`/plataforma/clientes?limite=20&offset=${offset}`:key==='equipe'?`/usuarios?limite=20&offset=${offset}`:key==='auditoria'?`/auditoria?limite=20&depois_id=${offset}`:query?`/remessas/localizar?valor=${encodeURIComponent(query)}&limite=20&offset=${offset}`:`/remessas?limite=20&offset=${offset}`;
    const data=await api.request(path); if (ticket!==view) return;
    const rows=query&&key==='cargas'?data.candidatas:data; results.replaceChildren();
    if (!rows.length) results.append(empty('Nenhum registro nesta consulta',key==='cargas'?'Confira o código ou documento. Cargas e leituras que ainda estão só no celular não aparecem aqui.':'Não há registros disponíveis nesta página.'));
    else if (key==='cargas') results.append(table(['Carga','Situação','Destinatário','Criada em'],rows.map(r=>[button(r.codigo,()=>detail(r.id),'link-button'),badge(statuses[r.status]||r.status),r.destinatario_nome,date(r.criado_em)])));
    else if (key==='empresas') results.append(table(['Empresa','CNPJ','Cadastro'],rows.map(r=>[r.razao_social,r.cnpj,badge(r.ativa?'Ativo':'Inativo')])));
    else if (key==='equipe') results.append(table(['Pessoa','E-mail','Permissão','Cadastro'],rows.map(r=>[r.nome,r.email,r.papel==='gestor'?'Gestor':'Operador',badge(r.ativo?'Ativo':'Inativo')])));
    else results.append(table(['Registro','Data e hora','Ação','Entidade','Responsável'],rows.map(r=>[String(r.id),date(r.em),r.acao,r.entidade,r.usuario_id||'Sistema'])));
    const pager=el('div',undefined,'pager'), controls=el('div',undefined,'controls');
    pager.append(el('span',`${rows.length} registro(s) nesta página`));
    if(offset>0) controls.append(button(key==='auditoria'?'Voltar ao início':'Anterior',()=>navigate(key,key==='auditoria'?0:Math.max(0,offset-20),query)));
    const more=query&&key==='cargas'?data.ha_mais:rows.length===20;
    if(more) controls.append(button('Próxima',()=>navigate(key,key==='auditoria'?rows.at(-1).id:offset+20,query)));
    pager.append(controls); results.append(pager);
  } catch(error) { if(ticket===view) { results.replaceChildren(); showError(error,results); } }
}

async function detail(id) {
  const ticket=++view; content.replaceChildren(button('← Voltar às cargas',()=>navigate('cargas'),'secondary back'),el('p','Carregando carga…','loading'));
  try {
    const r=await api.request(`/remessas/${id}`); if(ticket!==view)return;
    content.replaceChildren(button('← Voltar às cargas',()=>navigate('cargas'),'secondary back'),heading(r.codigo,r.descricao_carga||'Detalhes e evidências da carga'));
    content.append(notice('Escolha uma coleta para consultar o gráfico e baixar os registros recebidos. Cada gráfico mostra o histórico daquele bipe; não soma coletas repetidas. A confirmação de parada da etiqueta ainda permanece no aplicativo.'));
    const grid=el('div',undefined,'grid'), summary=el('section',undefined,'card'), docs=el('section',undefined,'card');
    summary.append(el('h2','Resumo da carga'),values([['Situação',statuses[r.status]||r.status],['Destinatário',r.destinatario_nome],['Criada em',date(r.criado_em)],['Intervalo',`${r.intervalo_segundos} segundos`]]));
    if(r.criterio) { const d=el('details');d.append(el('summary','Critério térmico preservado'),json(r.criterio));summary.append(d); }
    docs.append(el('h2','Documentos vinculados'));
    if(!r.documentos.length) docs.append(el('p','Nenhum documento vinculado.','muted'));
    r.documentos.forEach(d=>docs.append(values([['Documento',`${d.tipo} · ${d.numero||'Sem número'}`],['Chave',d.chave_acesso||'Não informada'],['Origem',d.digitado_manualmente?'Digitado manualmente':'Leitura do documento'],['Validação',d.validado?'Validado':'Pendente de conferência']])));
    grid.append(summary,docs); content.append(grid);
    const volumes=el('section',undefined,'card result'); volumes.append(el('h2','Volumes monitorados'));
    if(!r.volumes.length)volumes.append(el('p','Nenhum volume cadastrado.','muted'));
    r.volumes.forEach(v=>{ const box=el('div',undefined,'volume'), h=el('div',undefined,'volume-header'), result=el('div',undefined,'result'); h.append(el('strong',v.identidade||`Volume ${v.sequencia}`),button('Consultar coletas',e=>sessions(v.id,result,e.currentTarget,ticket))); box.append(h,result);volumes.append(box); });
    content.append(volumes);
  } catch(error) { if(ticket===view){ content.querySelector('.loading')?.remove(); showError(error,content); } }
}

async function action(target, trigger, ticket, load) {
  trigger.disabled=true; target.replaceChildren(el('p','Consultando…','loading'));
  try { const result=await load(); if(ticket===view)target.replaceChildren(result); }
  catch(error){if(ticket===view){target.replaceChildren();showError(error,target);}}
  finally{trigger.disabled=false;}
}

function sessions(id,target,trigger,ticket,offset=0) {
  return action(target,trigger,ticket,async()=>{
    const rows=await api.request(`/volumes/${id}/sessoes?limite=20&offset=${offset}`), list=el('div',undefined,'stack');
    if(!rows.length)list.append(empty('Sem sessões nesta página','Só aparecem as evidências que já chegaram ao servidor.'));
    for(const s of rows){
      const box=el('section',undefined,'card');box.append(el('h3',`Sessão · ${s.estado_logico}`),values([['Identificação',s.id],['Origem',s.origem==='simulacao'?'Simulação':'Declaração do Android'],['Faixa configurada',`${s.min_configurado_c} a ${s.max_configurado_c} °C`],['Intervalo',`${s.intervalo_segundos} segundos`]]));
      const readings=el('div',undefined,'result'), integrity=el('div',undefined,'result');
      box.append(button('Ver comprovantes',e=>receipts(s.id,readings,e.currentTarget,ticket)),button('Verificar integridade',e=>action(integrity,e.currentTarget,ticket,async()=>{
        const data=await api.request(`/sessoes/${s.id}/integridade`); const panel=el('div'); panel.append(notice(data.integra?'Cadeia de evidências íntegra. Isso não certifica a temperatura nem o sensor.':'A cadeia de evidências apresenta divergência. Encaminhe para conferência.'),json(data));return panel;
      }),'link-button'),readings,integrity);list.append(box);
    }
    if(offset>0)list.append(button('Sessões anteriores',e=>sessions(id,target,e.currentTarget,ticket,Math.max(0,offset-20))));
    if(rows.length===20)list.append(button('Mais sessões',e=>sessions(id,target,e.currentTarget,ticket,offset+20)));
    return list;
  });
}

function receipts(id,target,trigger,ticket,after=0){
  return action(target,trigger,ticket,async()=>{
    const rows=await api.request(`/sessoes/${id}/leituras?limite=20&apos_ordem=${after}`), panel=el('div'), evidence=el('div',undefined,'result');
    if(!rows.length) panel.append(el('p','Nenhum comprovante nesta página.','muted'));
    else panel.append(table(['Ordem','Coleta','Recebida no servidor','Temperaturas','Evidência'],rows.map(r=>[r.ordem_recebimento,r.tipo,date(r.recebida_em_servidor),button('Gráfico e relatório',e=>temperatures(id,r.leitura_id,evidence,e.currentTarget,ticket),'link-button'),button('Abrir',e=>action(evidence,e.currentTarget,ticket,async()=>json(await api.request(`/sessoes/${id}/leituras/${r.leitura_id}`))),'link-button')])));
    if(after>0)panel.append(button('Voltar ao início',e=>receipts(id,target,e.currentTarget,ticket)));
    if(rows.length===20)panel.append(button('Próximos comprovantes',e=>receipts(id,target,e.currentTarget,ticket,rows.at(-1).ordem_recebimento)));
    panel.append(evidence);return panel;
  });
}

function temperatures(session,id,target,trigger,ticket) {
  const path=`/sessoes/${session}/leituras/${id}/temperaturas`;
  return action(target,trigger,ticket,async()=>{
    const data=await api.request(path);
    return temperaturePanel(data,async(format,control,error)=>{
      control.disabled=true; error.textContent='';
      try {
        const blob=await api.request(`${path}?formato=${format}`,{download:true});
        if(ticket!==view)return;
        const url=URL.createObjectURL(blob),a=document.createElement('a');
        a.href=url; a.download=`ThermoTrace-${id}.${format}`; a.click();
        setTimeout(()=>URL.revokeObjectURL(url),1000);
      } catch(e) { if(ticket===view)showError(e,error); }
      finally { control.disabled=false; }
    });
  });
}

login();
