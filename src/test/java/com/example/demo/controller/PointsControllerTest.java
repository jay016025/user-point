package com.example.demo.controller;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionSystemException;

import com.example.demo.controller.mapper.PointsControllerMapperImpl;
import com.example.demo.exception.BusinessException;
import com.example.demo.exception.ErrorCode;
import com.example.demo.exception.SystemException;
import com.example.demo.service.PointsService;
import com.example.demo.service.command.DeletePointsCommand;
import com.example.demo.service.command.UpdateReasonCommand;
import com.example.demo.service.query.GetPointsQuery;
import com.example.demo.service.result.AddPointsResult;
import com.example.demo.service.result.DeletePointsResult;
import com.example.demo.service.result.GetLeaderboardResult;
import com.example.demo.service.result.GetPointsResult;
import com.example.demo.service.result.LeaderboardEntryResult;
import com.example.demo.service.result.UpdateReasonResult;

@WebMvcTest(PointsController.class)
@org.springframework.context.annotation.Import(PointsControllerMapperImpl.class)
class PointsControllerTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PointsService service;

    @Test
    void createsPointsAndReturnsRecordId() throws Exception {
        when(service.add(any())).thenReturn(new AddPointsResult(7, "user_1", 100, "bonus"));
        mvc.perform(post("/points")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user_1\",\"amount\":100,\"reason\":\"bonus\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.amount").value(100));
    }

    @Test
    void getsTotal() throws Exception {
        when(service.total(new GetPointsQuery("user_1"))).thenReturn(new GetPointsResult("user_1", 120));
        mvc.perform(get("/points/user_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("user_1"))
                .andExpect(jsonPath("$.data.total").value(120));
    }

    @Test
    void staticLeaderboardRouteHasPriority() throws Exception {
        when(service.leaderboard())
                .thenReturn(new GetLeaderboardResult(List.of(new LeaderboardEntryResult("winner", 950))));
        mvc.perform(get("/points/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries[0].userId").value("winner"))
                .andExpect(jsonPath("$.data.entries[0].total").value(950));
        verify(service, never()).total(any());
    }

    @Test
    void updatesReason() throws Exception {
        when(service.updateReason(any())).thenReturn(new UpdateReasonResult(7, "user_1", 100, "new"));
        mvc.perform(put("/points/7").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"new\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason").value("new"));
    }

    @Test
    void deletesUser() throws Exception {
        when(service.delete(any())).thenReturn(new DeletePointsResult("user_1"));
        mvc.perform(delete("/points/user_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("user_1"));
        verify(service).delete(new DeletePointsCommand("user_1"));
    }

    @Test
    void rejectsInvalidAmount() throws Exception {
        mvc.perform(post("/points")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user_1\",\"amount\":0,\"reason\":\"bonus\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("PTS0001"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsMissingAmount() throws Exception {
        mvc.perform(post("/points")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user_1\",\"reason\":\"bonus\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("PTS0001"));
    }

    @Test
    void rejectsFractionalAmountRatherThanTruncating() throws Exception {
        mvc.perform(post("/points")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user_1\",\"amount\":1.5,\"reason\":\"bonus\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("PTS0001"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsInvalidPathAndMalformedJson() throws Exception {
        mvc.perform(put("/points/not-a-number")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("PTS0001"));
        mvc.perform(put("/points/-1").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("PTS0001"));
        mvc.perform(post("/points").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("PTS0001"));
    }

    @Test
    void rejectsInvalidUserIdAndBlankReason() throws Exception {
        mvc.perform(get("/points/bad.user"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("PTS0001"));
        mvc.perform(put("/points/7").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("PTS0001"));
    }

    @Test
    void mapsBusinessErrorToNotFound() throws Exception {
        when(service.updateReason(any())).thenThrow(new BusinessException(ErrorCode.RECORD_NOT_FOUND));
        mvc.perform(put("/points/7").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("PTS0002"));
    }

    @Test
    void hidesDatabaseDetails() throws Exception {
        when(service.total(any()))
                .thenThrow(new SystemException(ErrorCode.DATABASE_ERROR, new RuntimeException("secret SQL password")));
        mvc.perform(get("/points/user_1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.code").value("SYS0002"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("secret SQL"))));
    }

    @Test
    void handlesDatabaseAndCommitErrorsDirectlyWithoutLeakingDetails() throws Exception {
        final List<RuntimeException> failures = List.of(
                new DataAccessResourceFailureException("internal database details"),
                new PersistenceException("internal database details"),
                new TransactionSystemException("internal database details"));
        for (final RuntimeException failure : failures) {
            doThrow(failure).when(service).total(any());
            mvc.perform(get("/points/user_1"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data.code").value("SYS0002"))
                    .andExpect(jsonPath("$.data.message").value("資料庫暫時無法處理請求"))
                    .andExpect(jsonPath("$.data.traceId").isNotEmpty())
                    .andExpect(content().string(Matchers.not(Matchers.containsString("internal database details"))));
        }
    }

    @Test
    void exhaustedOptimisticConflictsReturnConflictWithoutLeakingDetails() throws Exception {
        final List<RuntimeException> failures = List.of(
                new OptimisticLockingFailureException("internal version details"),
                new OptimisticLockException("internal version details"));
        for (final RuntimeException failure : failures) {
            doThrow(failure).when(service).add(any());
            mvc.perform(post("/points")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"user_1\",\"amount\":10,\"reason\":\"bonus\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.data.code").value("PTS0004"))
                    .andExpect(jsonPath("$.data.message").value("加入積分失敗，請稍後重試"))
                    .andExpect(content().string(Matchers.not(Matchers.containsString("internal version details"))));
        }
    }

    @Test
    void hidesUnexpectedErrors() throws Exception {
        when(service.total(any())).thenThrow(new IllegalStateException("internal path"));
        mvc.perform(get("/points/user_1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.data.code").value("SYS0001"));
    }

    @Test
    void frameworkErrorsKeepTheSameEnvelope() throws Exception {
        mvc.perform(patch("/points/user_1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.data.code").value("SYS0006"));
        mvc.perform(post("/points").contentType(MediaType.TEXT_PLAIN).content("text"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.data.code").value("SYS0007"));
        mvc.perform(get("/unknown-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("SYS0005"));
    }

    @Test
    void totalLimitIsConflictAndGenericSystemFailureIsInternalError() throws Exception {
        when(service.add(any())).thenThrow(new BusinessException(ErrorCode.POINTS_LIMIT_EXCEEDED));
        mvc.perform(post("/points")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user_1\",\"amount\":1,\"reason\":\"bonus\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("PTS0003"));
        when(service.total(any()))
                .thenThrow(new SystemException(ErrorCode.SYSTEM_ERROR, new IllegalStateException("internal")));
        mvc.perform(get("/points/user_1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.data.code").value("SYS0001"));
    }

    @Test
    void queryParametersCannotOverridePathVariable() throws Exception {
        when(service.total(new GetPointsQuery("user_1"))).thenReturn(new GetPointsResult("user_1", 120));
        mvc.perform(get("/points/user_1").param("userId", "other"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("user_1"));
        verify(service).total(new GetPointsQuery("user_1"));
        verify(service, never()).total(new GetPointsQuery("other"));
    }

    @Test
    void updateBodyCannotOverrideRecordIdentityOrAmount() throws Exception {
        when(service.updateReason(new UpdateReasonCommand(7, "new")))
                .thenReturn(new UpdateReasonResult(7, "user_1", 100, "new"));
        mvc.perform(put("/points/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"new\",\"id\":999,\"amount\":999,\"userId\":\"other\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.amount").value(100))
                .andExpect(jsonPath("$.data.userId").value("user_1"));
        verify(service).updateReason(new UpdateReasonCommand(7, "new"));
    }
}
