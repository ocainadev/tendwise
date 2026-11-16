import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import type { MembershipRole } from '../api/auth'
import { ApiError } from '../api/client'
import {
  ALREADY_MEMBER,
  changeRole,
  createInvitation,
  LAST_OWNER,
  listInvitations,
  listMembers,
  removeMember,
  revokeInvitation,
  type Member,
} from '../api/team'
import { ME_QUERY_KEY, useCurrentUser, useMembership } from '../auth/session'
import { useToast } from '../components/Shell/Toast'
import { formatDateTime } from '../knowledge/format'
import './Settings.css'

const ROLES: MembershipRole[] = ['OWNER', 'AGENT']

function membersKey(slug: string) {
  return ['team', slug, 'members']
}

function invitationsKey(slug: string) {
  return ['team', slug, 'invitations']
}

// A translated message for a failed team request.
function useTeamErrorMessage(): (error: unknown) => string {
  const { t } = useTranslation()
  return (error) => {
    if (error instanceof ApiError) {
      if (error.status === 0) return t('errors.network')
      if (error.problem.title === LAST_OWNER) return t('team.errors.lastOwner')
      if (error.problem.title === ALREADY_MEMBER) return t('team.errors.alreadyMember')
      if (error.status === 404) return t('team.errors.notFound')
      if (error.status === 400) return t('team.errors.invalidEmail')
    }
    return t('errors.generic')
  }
}

// /app/:slug/team (OWNER only): members with their role, pending invitations
// and the invite form. The API keeps at least one OWNER (409 "Last owner").
export function TeamPage() {
  const { t } = useTranslation()
  const membership = useMembership()
  const slug = membership?.slug ?? ''
  const me = useCurrentUser()
  const queryClient = useQueryClient()
  const errorMessage = useTeamErrorMessage()
  const { toast, showToast } = useToast()

  const members = useQuery({ queryKey: membersKey(slug), queryFn: () => listMembers(slug) })
  const invitations = useQuery({ queryKey: invitationsKey(slug), queryFn: () => listInvitations(slug) })
  // The member whose removal waits for "Yes, remove".
  const [confirmRemove, setConfirmRemove] = useState<Member | null>(null)
  const [inviteEmail, setInviteEmail] = useState('')
  const [inviteRole, setInviteRole] = useState<MembershipRole>('AGENT')

  // After a change to my own membership, my session's role is stale.
  function refreshIfMe(member: Member) {
    if (member.userId === me.user.id) {
      void queryClient.invalidateQueries({ queryKey: ME_QUERY_KEY })
    }
  }

  const roleChange = useMutation({
    mutationFn: ({ member, role }: { member: Member; role: MembershipRole }) => changeRole(slug, member.userId, role),
    onSuccess: (_, { member }) => {
      showToast(t('team.roleChanged', { name: member.name }))
      refreshIfMe(member)
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: membersKey(slug) }),
  })

  const removal = useMutation({
    mutationFn: (member: Member) => removeMember(slug, member.userId),
    onSuccess: (_, member) => {
      setConfirmRemove(null)
      showToast(t('team.removed', { name: member.name }))
      refreshIfMe(member)
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: membersKey(slug) }),
  })

  const invite = useMutation({
    mutationFn: () => createInvitation(slug, inviteEmail.trim(), inviteRole),
    onSuccess: (created) => {
      showToast(t('team.invite.sent', { email: created.email }))
      setInviteEmail('')
      setInviteRole('AGENT')
      void queryClient.invalidateQueries({ queryKey: invitationsKey(slug) })
    },
  })

  const revoke = useMutation({
    mutationFn: (id: number) => revokeInvitation(slug, id),
    onSettled: () => queryClient.invalidateQueries({ queryKey: invitationsKey(slug) }),
  })

  function handleInvite(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    invite.mutate()
  }

  // One alert for the last failed member action.
  const memberError = roleChange.error ?? removal.error

  return (
    <div className="team-page">
      <div>
        <h1>{t('team.title')}</h1>
        <p className="knowledge-muted">{t('team.description')}</p>
      </div>

      <section className="settings-section" aria-label={t('team.members')}>
        <h2>{t('team.members')}</h2>
        {memberError && (
          <p className="shell-error" role="alert">
            {errorMessage(memberError)}
          </p>
        )}
        {members.isPending && <p>{t('common.loading')}</p>}
        {members.isError && (
          <p className="shell-error" role="alert">
            {errorMessage(members.error)}
          </p>
        )}
        {members.isSuccess && (
          <table className="knowledge-table">
            <thead>
              <tr>
                <th>{t('team.columns.name')}</th>
                <th>{t('team.columns.email')}</th>
                <th>{t('team.columns.role')}</th>
                <th>{t('team.columns.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {members.data.map((member) => (
                <tr key={member.userId}>
                  <td>
                    {member.name} {member.userId === me.user.id && <span className="knowledge-muted">{t('team.you')}</span>}
                  </td>
                  <td>{member.email}</td>
                  <td>
                    <select
                      className="shell-select"
                      aria-label={t('team.roleLabel', { name: member.name })}
                      value={member.role}
                      disabled={roleChange.isPending}
                      onChange={(event) => {
                        removal.reset()
                        roleChange.mutate({ member, role: event.target.value as MembershipRole })
                      }}
                    >
                      {ROLES.map((role) => (
                        <option key={role} value={role}>
                          {t(`panel.roles.${role}`)}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td>
                    {confirmRemove?.userId === member.userId ? (
                      <div className="team-confirm">
                        <p>{t('team.removeConfirm', { name: member.name })}</p>
                        <button
                          type="button"
                          className="shell-button shell-button--danger"
                          disabled={removal.isPending}
                          onClick={() => {
                            roleChange.reset()
                            removal.mutate(member)
                          }}
                        >
                          {t('team.removeConfirmButton')}
                        </button>
                        <button
                          type="button"
                          className="shell-button shell-button--secondary"
                          onClick={() => setConfirmRemove(null)}
                        >
                          {t('team.cancel')}
                        </button>
                      </div>
                    ) : (
                      <button
                        type="button"
                        className="shell-button shell-button--secondary"
                        onClick={() => {
                          removal.reset()
                          setConfirmRemove(member)
                        }}
                      >
                        {t('team.remove')}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="settings-section" aria-label={t('team.invitations')}>
        <h2>{t('team.invitations')}</h2>
        {revoke.isError && (
          <p className="shell-error" role="alert">
            {errorMessage(revoke.error)}
          </p>
        )}
        {invitations.isPending && <p>{t('common.loading')}</p>}
        {invitations.isError && (
          <p className="shell-error" role="alert">
            {errorMessage(invitations.error)}
          </p>
        )}
        {invitations.isSuccess && invitations.data.length === 0 && <p>{t('team.noInvitations')}</p>}
        {invitations.isSuccess && invitations.data.length > 0 && (
          <ul className="settings-domain-list">
            {invitations.data.map((invitation) => (
              <li key={invitation.id}>
                <strong>{invitation.email}</strong>
                <span>{t(`panel.roles.${invitation.role}`)}</span>
                <span className="knowledge-muted knowledge-small">
                  {t('team.expiresAt', { date: formatDateTime(invitation.expiresAt) })}
                </span>
                <button
                  type="button"
                  className="shell-button shell-button--secondary"
                  aria-label={t('team.revokeLabel', { email: invitation.email })}
                  disabled={revoke.isPending}
                  onClick={() => revoke.mutate(invitation.id)}
                >
                  {t('team.revoke')}
                </button>
              </li>
            ))}
          </ul>
        )}

        <form className="settings-row" onSubmit={handleInvite} aria-label={t('team.invite.title')}>
          <label className="settings-field">
            {t('team.invite.email')}
            <input
              type="email"
              required
              maxLength={254}
              value={inviteEmail}
              onChange={(event) => setInviteEmail(event.target.value)}
            />
          </label>
          <label className="settings-field">
            {t('team.invite.role')}
            <select
              className="shell-select"
              value={inviteRole}
              onChange={(event) => setInviteRole(event.target.value as MembershipRole)}
            >
              {ROLES.map((role) => (
                <option key={role} value={role}>
                  {t(`panel.roles.${role}`)}
                </option>
              ))}
            </select>
          </label>
          <button type="submit" className="shell-button settings-invite-submit" disabled={invite.isPending}>
            {invite.isPending ? t('team.invite.submitting') : t('team.invite.submit')}
          </button>
        </form>
        {invite.isError && (
          <p className="shell-error" role="alert">
            {errorMessage(invite.error)}
          </p>
        )}
      </section>
      {toast}
    </div>
  )
}
