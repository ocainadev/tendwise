import { Navigate, Route, Routes } from 'react-router'
import { useMembership } from '../auth/session'
import { DocumentEditorPage } from './DocumentEditorPage'
import { DocumentListPage } from './DocumentListPage'
import { Playground } from './Playground'
import './Knowledge.css'

// /app/:slug/knowledge/*: the document list, the editor and, for the owner,
// the playground next to them, so an edit can be tested right after saving.
// AGENT reads only: the API refuses their writes too (T50).
export function KnowledgeArea() {
  const membership = useMembership()
  if (!membership) {
    return null // PanelLayout already sent non-members away.
  }
  const isOwner = membership.role === 'OWNER'
  const slug = membership.slug

  return (
    <div className="knowledge-layout">
      <div className="knowledge-content">
        <Routes>
          <Route index element={<DocumentListPage slug={slug} isOwner={isOwner} />} />
          <Route
            path="new"
            element={isOwner ? <DocumentEditorPage slug={slug} isOwner /> : <Navigate to=".." relative="path" replace />}
          />
          <Route path=":documentId" element={<DocumentEditorPage slug={slug} isOwner={isOwner} />} />
          <Route path="*" element={<DocumentListPage slug={slug} isOwner={isOwner} />} />
        </Routes>
      </div>
      {isOwner && <Playground slug={slug} />}
    </div>
  )
}
