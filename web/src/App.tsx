import { useTranslation } from 'react-i18next'
import { Navigate, Route, Routes } from 'react-router'
import { ADMIN_AREAS, AdminLayout } from './admin/AdminLayout'
import { RequireAuth } from './auth/RequireAuth'
import { Chat } from './components/Chat'
import { BusinessChooserPage } from './pages/BusinessChooserPage'
import { ComingSoonPage } from './pages/ComingSoonPage'
import { LoginPage } from './pages/LoginPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { DEFAULT_PANEL_AREA, PANEL_AREAS } from './panel/menu'
import { PanelLayout } from './panel/PanelLayout'
import { RequireRole } from './panel/RequireRole'

// Every page of the web app. The router itself (BrowserRouter in main.tsx,
// MemoryRouter in tests) wraps this component.
export function App() {
  const { t } = useTranslation()

  return (
    <Routes>
      {/* The root still opens the demo chat, as in v0.1.0 (README). */}
      <Route path="/" element={<Navigate to="/demo" replace />} />
      <Route path="/demo" element={<Chat />} />
      <Route path="/login" element={<LoginPage />} />

      <Route element={<RequireAuth />}>
        <Route path="/app" element={<BusinessChooserPage />} />
        <Route path="/app/:slug" element={<PanelLayout />}>
          <Route index element={<Navigate to={DEFAULT_PANEL_AREA} replace />} />
          {PANEL_AREAS.map((area) => (
            <Route
              key={area.path}
              path={area.path}
              element={
                <RequireRole roles={area.roles}>
                  <ComingSoonPage title={t(area.labelKey)} />
                </RequireRole>
              }
            />
          ))}
          <Route path="*" element={<Navigate to={DEFAULT_PANEL_AREA} replace />} />
        </Route>
        <Route path="/admin" element={<AdminLayout />}>
          <Route index element={<Navigate to={ADMIN_AREAS[0].path} replace />} />
          {ADMIN_AREAS.map((area) => (
            <Route key={area.path} path={area.path} element={<ComingSoonPage title={t(area.labelKey)} />} />
          ))}
          <Route path="*" element={<Navigate to={ADMIN_AREAS[0].path} replace />} />
        </Route>
      </Route>

      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}
