const ns='http://www.w3.org/2000/svg';
export function geometry(points,range) {
  if(!Array.isArray(points)||points.length>65535||!Number.isFinite(range.min_c)||!Number.isFinite(range.max_c)||range.min_c>=range.max_c)throw new Error('Faixa ou série inválida.');
  let low=range.min_c,high=range.max_c;
  for(const p of points){if(!Number.isFinite(p.temperatura_c)||!Number.isFinite(Date.parse(p.instante)))throw new Error('Ponto inválido.');low=Math.min(low,p.temperatura_c);high=Math.max(high,p.temperatura_c);}
  const pad=Math.max((high-low)*.15,1); low-=pad;high+=pad;
  const x=i=>75+(points.length>1?i/(points.length-1):.5)*790,y=t=>320-(t-low)/(high-low)*260;
  return {low,high,x,y,line:points.map((p,i)=>`${x(i).toFixed(2)},${y(p.temperatura_c).toFixed(2)}`).join(' ')};
}
function node(tag,text,cls){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;}
function graphic(tag,attrs,text){const n=document.createElementNS(ns,tag);for(const [k,v] of Object.entries(attrs))n.setAttribute(k,String(v));if(text!==undefined)n.textContent=text;return n;}
const date=v=>new Intl.DateTimeFormat('pt-BR',{dateStyle:'short',timeStyle:'medium'}).format(new Date(v));
const number=v=>v===null?'—':new Intl.NumberFormat('pt-BR',{maximumFractionDigits:3}).format(v);

export function temperaturePanel(data,download){
  const panel=node('section',undefined,'temperature-panel');
  panel.append(node('h3','Temperaturas desta coleta'),node('p',`${data.tipo} · coletada em ${date(data.lida_em)} · ${data.origem==='simulacao'?'SIMULAÇÃO':'Declaração do aplicativo'}`,'muted'));
  const labels={coerente:'Cabeçalho e série coerentes',divergente:'Divergência: precisa de conferência',nao_avaliavel:'Conferência não avaliável'};
  panel.append(node('p',labels[data.conferencia]||'Conferência indisponível',`thermal-status ${data.conferencia==='coerente'?'coherent':'attention'}`));
  for(const a of data.avisos)panel.append(node('p',a,'notice'));
  const points=data.pontos;
  if(points.length){
    const g=geometry(points,data.faixa),kpis=node('div',undefined,'thermal-kpis');
    for(const [label,value] of [['Medições',points.length],['Mínima',`${number(data.resumo.minima_c)} °C`],['Máxima',`${number(data.resumo.maxima_c)} °C`],['Fora da faixa',data.resumo.abaixo+data.resumo.acima]]){
      const box=node('div');box.append(node('span',label),node('strong',String(value)));kpis.append(box);
    }
    panel.append(kpis);
    const svg=graphic('svg',{viewBox:'0 0 900 390',role:'img','aria-label':'Gráfico de temperaturas nominais desta coleta',class:'thermal-chart'});
    svg.append(graphic('title',{},'Temperaturas da coleta e faixa configurada'),graphic('rect',{x:75,y:g.y(data.faixa.max_c),width:790,height:g.y(data.faixa.min_c)-g.y(data.faixa.max_c),fill:'#dbf5ed'}));
    for(let i=0;i<5;i++){const t=g.low+(g.high-g.low)*i/4;svg.append(graphic('line',{x1:75,x2:865,y1:g.y(t),y2:g.y(t),stroke:'#d9e5e9'}),graphic('text',{x:64,y:g.y(t)+5,'text-anchor':'end'},number(t)));}
    svg.append(graphic('text',{x:20,y:38},'°C'),graphic('polyline',{points:g.line,fill:'none',stroke:'#007f94','stroke-width':2}),graphic('text',{x:75,y:352},date(points[0].instante)),graphic('text',{x:865,y:374,'text-anchor':'end'},date(points.at(-1).instante)));
    const dot=graphic('circle',{r:5,fill:'#ad4918',cx:g.x(0),cy:g.y(points[0].temperatura_c)});svg.append(dot);const frame=node('div',undefined,'chart-frame');frame.append(svg);panel.append(frame);
    panel.append(node('p',`Faixa verde: ${number(data.faixa.min_c)} a ${number(data.faixa.max_c)} °C. Horários nominais, exibidos no fuso deste navegador.`,'hint'));
    const label=node('label','Explorar cada medição','field'),input=node('input'),selected=node('p',undefined,'sample-detail');
    input.type='range';input.min='0';input.max=String(points.length-1);input.step='1';input.value='0';
    function choose(i){const p=points[i];input.value=String(i);dot.setAttribute('cx',g.x(i));dot.setAttribute('cy',g.y(p.temperatura_c));selected.textContent=`Medição ${i+1} de ${points.length} · ${date(p.instante)} · ${number(p.temperatura_c)} °C`;input.setAttribute('aria-valuetext',selected.textContent);}
    input.addEventListener('input',()=>choose(Number(input.value)));svg.addEventListener('pointerdown',e=>{const r=svg.getBoundingClientRect();choose(Math.max(0,Math.min(points.length-1,Math.round(((e.clientX-r.left)/r.width*900-75)/790*(points.length-1)))));});
    label.append(input);panel.append(label,selected);choose(0);
  }else panel.append(node('p','Sem série disponível para desenhar. Consulte os avisos e a evidência original.','muted'));
  const actions=node('div',undefined,'thermal-actions'),error=node('div');error.setAttribute('role','alert');
  for(const [format,label] of [['csv','Baixar medições (CSV)'],['svg','Baixar gráfico (SVG)'],['html','Baixar relatório']]){
    const b=node('button',label,'secondary');b.type='button';b.addEventListener('click',()=>download(format,b,error));actions.append(b);
  }
  panel.append(actions,error,node('p','O relatório pode ser impresso ou salvo em PDF pelo navegador. Recebimento e coerência interna não comprovam calibração, parada da etiqueta ou liberação do produto.','hint'));
  const provenance=node('details');provenance.append(node('summary','Origem e rastreabilidade'),node('p',`Coleta: ${data.leitura_id}\nVersão de cálculo: ${data.versao}\nHash da evidência: ${data.hash_evidencia}\nHash desta projeção: ${data.hash_projecao}`,'sample-detail'));
  panel.append(provenance);return panel;
}
