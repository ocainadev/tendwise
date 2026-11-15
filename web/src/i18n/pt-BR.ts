// Panel texts in Brazilian Portuguese, the first (and for now only) locale.
// To add English or Spanish, copy this file to en.ts / es.ts, type the copy as
// `Messages` (so TypeScript reports a missing or extra key) and register it in
// ./index.ts. The demo chat in /demo is not translated here: it keeps its own
// English texts until the widget replaces it (T56).

export const ptBR = {
  common: {
    appName: 'Tendwise',
    loading: 'Carregando…',
    logout: 'Sair',
    comingSoon: 'Em breve',
    comingSoonDescription: 'Esta área ainda está em construção.',
  },
  errors: {
    generic: 'Algo deu errado. Tente novamente.',
    network: 'Não foi possível falar com o servidor. Verifique sua conexão e tente novamente.',
    retry: 'Tentar novamente',
    notFound: 'Página não encontrada.',
    backHome: 'Voltar ao início',
  },
  login: {
    title: 'Entrar no Tendwise',
    email: 'E-mail',
    password: 'Senha',
    submit: 'Entrar',
    submitting: 'Entrando…',
    // One message for every failure, as the API does (wrong email, wrong
    // password, locked account): it must not tell them apart.
    invalidCredentials: 'E-mail ou senha incorretos.',
  },
  chooser: {
    title: 'Escolha uma empresa',
    none: 'Sua conta ainda não tem acesso a nenhuma empresa.',
    adminLink: 'Abrir a administração da plataforma',
  },
  panel: {
    navLabel: 'Menu da empresa',
    switcherLabel: 'Empresa',
    roles: {
      OWNER: 'Proprietário',
      AGENT: 'Atendente',
    },
    menu: {
      inbox: 'Caixa de entrada',
      knowledge: 'Conhecimento',
      catalog: 'Catálogo',
      orders: 'Pedidos',
      dashboard: 'Relatórios',
      settings: 'Configurações',
      team: 'Equipe',
    },
  },
  admin: {
    title: 'Administração',
    navLabel: 'Menu da administração',
    menu: {
      overview: 'Visão geral',
      businesses: 'Empresas',
      plans: 'Planos',
      auditLog: 'Registro de auditoria',
    },
  },
}

export type Messages = typeof ptBR
