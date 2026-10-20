package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.example.demo.domain.PointRecord;
import com.example.demo.domain.PointsAccount;
import com.example.demo.domain.PointsChanged;
import com.example.demo.domain.PointsEventType;
import com.example.demo.exception.BusinessException;
import com.example.demo.exception.ErrorCode;
import com.example.demo.persistence.leaderboard.LeaderboardRepository;
import com.example.demo.persistence.points.PointsRepository;
import com.example.demo.service.command.AddPointsCommand;
import com.example.demo.service.command.DeletePointsCommand;
import com.example.demo.service.command.UpdateReasonCommand;
import com.example.demo.service.impl.PointsServiceImpl;
import com.example.demo.service.mapper.PointsServiceMapper;
import com.example.demo.service.query.GetPointsQuery;
import com.example.demo.service.result.LeaderboardEntryResult;
import com.example.demo.service.result.UpdateReasonResult;

class PointsServiceTest {
    private final PointsRepository repository = mock(PointsRepository.class);
    private final LeaderboardRepository leaderboard = mock(LeaderboardRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PointsDeletionService deletions = mock(PointsDeletionService.class);
    private PointsService service;

    @BeforeEach
    void setUp() {
        service = new PointsServiceImpl(
                repository,
                leaderboard,
                events,
                Mappers.getMapper(PointsServiceMapper.class),
                new PointsTotalReader(repository),
                deletions);
    }

    @Test
    void addPersistsRecordTotalAndDurableEvent() {
        when(repository.findOrCreateAccount("user_1")).thenReturn(new PointsAccount("user_1", 20, 3));
        when(repository.saveAccount(new PointsAccount("user_1", 120, 3)))
                .thenReturn(new PointsAccount("user_1", 120, 4));
        when(repository.insert(new PointRecord(0, "user_1", 100, "bonus")))
                .thenReturn(new PointRecord(7, "user_1", 100, "bonus"));
        assertThat(service.add(new AddPointsCommand("user_1", 100, "bonus")).id())
                .isEqualTo(7);
        verify(repository).saveAccount(new PointsAccount("user_1", 120, 3));
        final ArgumentCaptor<PointsChanged> event = ArgumentCaptor.forClass(PointsChanged.class);
        verify(repository).enqueue(event.capture());
        assertThat(event.getValue().total()).isEqualTo(120);
        assertThat(event.getValue().version()).isEqualTo(4);
        assertThat(event.getValue().type()).isEqualTo(PointsEventType.POINTS_ADDED);
        assertThat(event.getValue().eventId()).isNotBlank();
        verify(events).publishEvent(event.getValue());
    }

    @Test
    void rejectsNonPositiveAmountBeforeWriting() {
        assertThatThrownBy(() -> service.add(new AddPointsCommand("user_1", 0, "bonus")))
                .isInstanceOfSatisfying(
                        BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsOverflowWithoutWritingRecord() {
        when(repository.findOrCreateAccount("user_1")).thenReturn(new PointsAccount("user_1", 9007199254740991L, 0));
        assertThatThrownBy(() -> service.add(new AddPointsCommand("user_1", 1, "bonus")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode())
                        .isEqualTo(ErrorCode.POINTS_LIMIT_EXCEEDED));
        verify(repository, never()).insert(any());
    }

    @Test
    void missingUserHasZeroPoints() {
        assertThat(service.total(new GetPointsQuery("unknown")).total()).isZero();
    }

    @Test
    void updateOnlyChangesReason() {
        when(repository.findRecord(7)).thenReturn(Optional.of(new PointRecord(7, "user_1", 100, "old")));
        when(repository.updateReason(7, "new")).thenReturn(true);
        assertThat(service.updateReason(new UpdateReasonCommand(7, "new")))
                .isEqualTo(new UpdateReasonResult(7, "user_1", 100, "new"));
        verify(repository, never()).saveAccount(any());
        verify(repository, never()).enqueue(any());
    }

    @Test
    void missingRecordIsBusinessError() {
        when(repository.findRecord(7)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateReason(new UpdateReasonCommand(7, "new")))
                .isInstanceOfSatisfying(
                        BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RECORD_NOT_FOUND));
    }

    @Test
    void deletionClearsLeaderboardWithoutPublishingEvents() {
        final PointsAccount deleted = new PointsAccount("user_1", 0, 5);
        when(deletions.delete("user_1")).thenReturn(Optional.of(deleted));
        assertThat(service.delete(new DeletePointsCommand("user_1")).userId()).isEqualTo("user_1");
        verify(deletions).delete("user_1");
        verify(leaderboard).remove(deleted);
        verifyNoInteractions(repository, events);
    }

    @Test
    void deletionOfMissingUserSucceedsWithoutCreatingDataOrEvents() {
        when(deletions.delete("missing")).thenReturn(Optional.empty());
        assertThat(service.delete(new DeletePointsCommand("missing")).userId()).isEqualTo("missing");
        verifyNoInteractions(repository, leaderboard, events);
    }

    @Test
    void leaderboardUsesReadPort() {
        when(leaderboard.topTen()).thenReturn(List.of(new PointsAccount("winner", 1000, 0)));
        assertThat(service.leaderboard().entries())
                .extracting(LeaderboardEntryResult::userId)
                .containsExactly("winner");
        verifyNoInteractions(repository);
    }

    @Test
    void emptyLeaderboardLoadsDatabaseAndRestoresAllAccounts() {
        final List<PointsAccount> snapshot = List.of(
                new PointsAccount("winner", 1000, 3),
                new PointsAccount("runner", 500, 2),
                new PointsAccount("deleted", 0, 4));
        when(repository.findLeaderboardSnapshot()).thenReturn(snapshot);
        assertThat(service.leaderboard().entries())
                .extracting(LeaderboardEntryResult::userId)
                .containsExactly("winner", "runner");
        verify(leaderboard).restore(snapshot);
        verifyNoInteractions(events);
    }

    @Test
    void emptyDatabaseReturnsEmptyLeaderboard() {
        assertThat(service.leaderboard().entries()).isEmpty();
        verify(repository).findLeaderboardSnapshot();
        verify(leaderboard, never()).restore(any());
    }
}
