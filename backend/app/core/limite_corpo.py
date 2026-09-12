"""Limite antes de JSON/Pydantic; cabeçalhos de tamanho não são confiados."""
from starlette.responses import JSONResponse

class LimitarCorpo:
    def __init__(self, app, limite=1048576):
        self.app=app;self.limite=limite

    async def __call__(self, scope, receive, send):
        if scope['type']!='http' or scope['method'] not in ('POST','PUT','PATCH'):
            return await self.app(scope,receive,send)
        blocos=[];tamanho=0
        while True:
            mensagem=await receive()
            if mensagem['type']=='http.disconnect':return
            b=mensagem.get('body',b'');tamanho+=len(b)
            if tamanho>self.limite:
                resposta=JSONResponse({'erro':'corpo_muito_grande','mensagem':'Requisição excede o limite de 1 MiB.',
                                      'detalhes':{},'request_id':'-'},status_code=413)
                return await resposta(scope,receive,send)
            blocos.append(b)
            if not mensagem.get('more_body',False):break
        enviado=False
        async def corpo():
            nonlocal enviado
            if enviado:return await receive()
            enviado=True
            return {'type':'http.request','body':b''.join(blocos),'more_body':False}
        await self.app(scope,corpo,send)
